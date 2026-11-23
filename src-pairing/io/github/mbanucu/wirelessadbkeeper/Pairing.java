package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Wireless-debugging pairing, and a check that the pairing took.
 *
 * This is the one part of the app that cannot be written against android.jar
 * alone: pairing is SPAKE2 plus a TLS certificate exchange, and re-implementing
 * that is a week of subtle crypto for one screen. So it lives in its own source
 * directory (`src-pairing/`) and reaches the app through libadb-android, which
 * only the Gradle build carries. `build.sh` still compiles `src/` on its own
 * and produces the Termux-backed APK unchanged.
 *
 * The port to pair on is not fixed and changes every time the system's pairing
 * dialog opens, so it comes from mDNS: Android advertises `_adb-tls-pairing._tcp`
 * while the dialog is up. That is also why the dialog has to stay open while
 * these calls run.
 */
public final class Pairing {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int DISCOVERY_MS = 15_000;
    private static final int CONNECT_MS = 10_000;
    private static final int VERIFY_MS = 20_000;
    private static final int PAIR_MS = 30_000;

    private Pairing() {}

    /** Pairs with this phone using the code the system dialog is showing. */
    public static String pair(Context context, String rawCode) {
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) {
            Diag.warn(context, "key.pair_invalid_code", "the pairing code is not 6 digits");
            return "Enter the 6-digit code shown in Developer options";
        }
        final AtomicReference<Closeable> live = new AtomicReference<>();
        try {
            Endpoint endpoint = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                    "Wireless debugging is not showing a pairing code. Open Developer options, "
                            + "tap \"Pair device with pairing code\", then try again.");
            final PrivateKey privateKey = loadKey(context);
            final X509Certificate certificate = AdbCertificate.loadOrCreate(context);

            within(PAIR_MS, live, new Attempt<Void>() {
                @Override
                public Void run() throws Exception {
                    PairingConnectionCtx pairing = new PairingConnectionCtx(
                            endpoint.address.getHostAddress(), endpoint.port,
                            code.getBytes(ASCII), privateKey, certificate, "pairing");
                    live.set(pairing);
                    try {
                        pairing.start();
                    } finally {
                        closeQuietly(pairing);
                    }
                    return null;
                }
            });
            Diag.info(context, "key.pair_ok", "the phone trusts the app's ADB key");
            return "Paired. The app's key is now trusted by this phone.";
        } catch (TimeoutException e) {
            Diag.warn(context, "key.pair_timeout", noAnswer(PAIR_MS));
            return "Pairing timed out: " + noAnswer(PAIR_MS) + ".";
        } catch (Exception e) {
            Diag.error(context, "key.pair_failed", message(e));
            return "Pairing failed: " + message(e);
        }
    }

    /**
     * Opens a shell over the wireless-debugging TLS port with the app's key.
     * This is what proves the pairing worked: adbd only answers once it trusts
     * the key that was presented.
     */
    public static String verify(Context context) {
        final AtomicReference<Closeable> live = new AtomicReference<>();
        try {
            Endpoint endpoint = discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT,
                    "Wireless debugging is off. Turn it on in Developer options, then try again.");
            final PrivateKey privateKey = loadKey(context);
            final X509Certificate certificate = AdbCertificate.loadOrCreate(context);

            return within(VERIFY_MS, live, new Attempt<String>() {
                @Override
                public String run() throws Exception {
                    AdbConnection connection = AdbConnection.create(
                            endpoint.address.getHostAddress(), endpoint.port,
                            privateKey, certificate);
                    live.set(connection);
                    try {
                        // Abort on an untrusted key instead of waiting for the
                        // on-device prompt, which this screen cannot show.
                        if (!connection.connect(CONNECT_MS, TimeUnit.MILLISECONDS, true)) {
                            throw new IOException(noAnswer(CONNECT_MS));
                        }
                        AdbStream stream = connection.open("shell:id");
                        live.set(stream);
                        byte[] buffer = new byte[256];
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        int n;
                        while ((n = stream.read(buffer, 0, buffer.length)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        String reply = new String(out.toByteArray(), Charset.forName("UTF-8")).trim();
                        String text = reply.isEmpty()
                                ? "Connected, but the shell said nothing back."
                                : "Connected as " + reply;
                        Diag.info(context, "key.verify_ok", text);
                        return text;
                    } finally {
                        connection.close();
                    }
                }
            });
        } catch (TimeoutException e) {
            Diag.warn(context, "key.verify_timeout", noAnswer(VERIFY_MS));
            return "No connection yet: " + noAnswer(VERIFY_MS) + ".";
        } catch (AdbAuthenticationFailedException e) {
            Diag.warn(context, "key.verify_untrusted",
                    "the phone does not trust this app's key; pair again");
            return "No connection yet: the phone does not trust this app's key - pair again.";
        } catch (Exception e) {
            Diag.error(context, "key.verify_failed", message(e));
            return "No connection yet: " + message(e);
        }
    }

    /**
     * Runs an attempt on its own thread so a blocked socket connect, handshake
     * or read cannot hold the caller forever. On timeout the attempt's live
     * connection is closed first, which is what wakes the library's unbounded
     * read; cancelling alone would only cover the interruptible waits.
     */
    private static <T> T within(long timeoutMs, AtomicReference<Closeable> live, Attempt<T> attempt)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(new Callable<T>() {
            @Override
            public T call() throws Exception {
                return attempt.run();
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeQuietly(live.get());
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    /** The app key; a missing file is the documented reset, so say so. */
    private static PrivateKey loadKey(Context context) throws IOException {
        boolean existed = new File(AdbIdentity.dir(context), AdbIdentity.KEY_FILE).isFile();
        PrivateKey key = AdbIdentity.load(context).privateKey();
        if (!existed) {
            Diag.info(context, "key.created", "no ADB key found; generated a new one;"
                    + " pair this app again");
        }
        return key;
    }

    private interface Attempt<T> {
        T run() throws Exception;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // The library can still be mid-handshake with no streams to close
            // yet; the cancelled future remains the bound in that case.
        }
    }

    private static String noAnswer(long timeoutMs) {
        return "the phone did not answer within "
                + TimeUnit.MILLISECONDS.toSeconds(timeoutMs) + " s";
    }

    /** One advertised adbd endpoint. */
    private static final class Endpoint {
        final InetAddress address;
        final int port;

        Endpoint(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    private static Endpoint discover(final Context context, String serviceType, String failure)
            throws IOException {
        final CountDownLatch found = new CountDownLatch(1);
        final AtomicReference<Endpoint> result = new AtomicReference<>();
        AdbMdns mdns = new AdbMdns(context, serviceType,
                new AdbMdns.OnAdbDaemonDiscoveredListener() {
                    @Override
                    public void onPortChanged(InetAddress address, int port) {
                        if (address == null || port <= 0 || port > 65535) return;
                        // Adbd advertises once per network; any address that
                        // resolves to this phone will do, and mDNS hands back
                        // one of them.
                        result.compareAndSet(null, new Endpoint(address, port));
                        found.countDown();
                    }
                });
        mdns.start();
        try {
            found.await(DISCOVERY_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mdns.stop();
        }
        Endpoint endpoint = result.get();
        if (endpoint == null) {
            throw new IOException(failure);
        }
        return endpoint;
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.isEmpty() ? t.getClass().getSimpleName() : text;
    }
}

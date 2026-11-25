package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.lsposed.hiddenapibypass.HiddenApiBypass;

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
    /** Boot tries to pin quickly; a later 10 s tick can retry. */
    private static final int PIN_DISCOVERY_MS = 6_000;
    private static final int PIN_CONNECT_MS = 6_000;
    private static final int PIN_MS = 12_000;
    private static final int SCAN_PORT_START = 30_000;
    private static final int SCAN_PORT_END = 60_999;
    private static final int MAX_SCAN_CANDIDATES = 8;

    private Pairing() {}

    /**
     * libadb asks Conscrypt for the TLS exporter through reflection. Android 9
     * made that a non-SDK method, so pairing gets NoSuchMethodException on a
     * modern targetSdk. Exempt only that class; everything else stays subject
     * to the normal hidden-API policy.
     */
    private static void allowConscryptExporter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Lcom/android/org/conscrypt/Conscrypt;");
        } catch (Throwable ignored) {
            // Without the exemption, pair()/verify() report the real failure.
        }
    }

    /** Pairs with this phone using the code the system dialog is showing. */
    public static String pair(Context context, String rawCode) {
        return pair(context, rawCode, null, 0);
    }

    /**
     * Pairs against an explicit endpoint. Used by the exported pairing screen
     * when mDNS has gone away because the system dialog is in the background;
     * the dialog's IP/port is still the authoritative pair endpoint.
     */
    public static String pair(Context context, String rawCode, String host, int port) {
        allowConscryptExporter();
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) {
            Diag.warn(context, "key.pair_invalid_code", "the pairing code is not 6 digits");
            return "Enter the 6-digit code shown in Developer options";
        }
        final AtomicReference<Closeable> live = new AtomicReference<>();
        try {
            Endpoint endpoint;
            if (host != null && !host.isEmpty() && port > 0 && port <= 65535) {
                endpoint = new Endpoint(InetAddress.getByName(host), port);
            } else {
                endpoint = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                        "Wireless debugging is not showing a pairing code. Open Developer options, "
                                + "tap \"Pair device with pairing code\", then try again.");
            }
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
        allowConscryptExporter();
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
                        try {
                            while ((n = stream.read(buffer, 0, buffer.length)) > 0) {
                                out.write(buffer, 0, n);
                            }
                        } catch (IOException e) {
                            // libadb 3.1.1 throws "Stream closed." when adbd
                            // closes a finished shell instead of returning -1
                            // (upstream issue #34). Everything read so far is
                            // still the command's output.
                            if (!"Stream closed.".equals(e.getMessage())) throw e;
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
     * Switches the Wi-Fi radio on with {@code shell:svc wifi enable} over the
     * app's own trusted TLS connection.
     *
     * <p>This is the canonical path for the Gradle build: it needs neither
     * Termux nor its scripts, only the app key adbd already trusts. Discovery
     * and the scan fallback are the same as {@link #pinPort(Context, int)}.
     * {@link WifiRadio} keeps Termux as the fallback when this returns false,
     * and journals which client took the request.
     */
    public static boolean enableWifi(Context context) {
        allowConscryptExporter();
        final PrivateKey privateKey;
        final X509Certificate certificate;
        try {
            privateKey = loadKey(context);
            certificate = AdbCertificate.loadOrCreate(context);
        } catch (Exception e) {
            Diag.warn(context, "wifi.tls_failed", "could not load the app key: " + message(e));
            return false;
        }

        Endpoint advertised = null;
        try {
            advertised = discoverOrNull(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, PIN_DISCOVERY_MS);
        } catch (Throwable e) {
            Diag.warn(context, "wifi.tls_failed", "mDNS discovery failed: " + message(e));
        }
        if (advertised != null
                && tryShell(context, advertised, "svc wifi enable", privateKey, certificate)) {
            return true;
        }

        // A stale or absent advertisement must not mean "no Wi-Fi": try the
        // listening local ports exactly as the pin path does.
        List<Endpoint> scanned = scanLocalPorts();
        for (int i = 0; i < scanned.size() && i < MAX_SCAN_CANDIDATES; i++) {
            if (tryShell(context, scanned.get(i), "svc wifi enable", privateKey, certificate)) {
                return true;
            }
        }
        Diag.warn(context, "wifi.tls_failed",
                "no in-app TLS endpoint could run `svc wifi enable`");
        return false;
    }

    private static boolean tryShell(final Context context, final Endpoint endpoint,
                                    final String command, final PrivateKey privateKey,
                                    final X509Certificate certificate) {
        final AtomicReference<Closeable> live = new AtomicReference<>();
        try {
            within(PIN_MS, live, new Attempt<Void>() {
                @Override
                public Void run() throws Exception {
                    AdbConnection connection = AdbConnection.create(
                            endpoint.address.getHostAddress(), endpoint.port,
                            privateKey, certificate);
                    live.set(connection);
                    try {
                        if (!connection.connect(PIN_CONNECT_MS, TimeUnit.MILLISECONDS, true)) {
                            throw new IOException(noAnswer(PIN_CONNECT_MS));
                        }
                        AdbStream stream = connection.open("shell:" + command);
                        live.set(stream);
                        byte[] buffer = new byte[256];
                        try {
                            while (stream.read(buffer, 0, buffer.length) > 0) {
                                // svc prints nothing on success; drain so adbd
                                // has run the command before the stream closes.
                            }
                        } catch (IOException e) {
                            // libadb 3.1.1 reports a finished shell this way
                            // (upstream issue #34); anything read still counts.
                            if (!"Stream closed.".equals(e.getMessage())) throw e;
                        }
                        try {
                            stream.close();
                        } catch (Exception ignored) {
                            // adbd may already have closed it.
                        }
                    } finally {
                        try {
                            connection.close();
                        } catch (Exception ignored) {
                            // Nothing left to release.
                        }
                    }
                    return null;
                }
            });
            return true;
        } catch (TimeoutException e) {
            Diag.warn(context, "wifi.tls_timeout", noAnswer(PIN_MS));
        } catch (AdbAuthenticationFailedException e) {
            Diag.warn(context, "wifi.tls_untrusted",
                    "the phone does not trust this app's key; pair again");
        } catch (Exception e) {
            Diag.warn(context, "wifi.tls_failed", message(e));
        }
        return false;
    }

    /**
     * Sends {@code tcpip:<port>} over the app's own trusted TLS connection.
     *
     * <p>This is the pre-unlock path: the key and certificate are in
     * device-protected storage, so the monitor can call it while the user is
     * still locked. Discovery is mDNS first; if the advertisement is gone, the
     * app reads its own listening sockets in the ephemeral range and tries the
     * candidates. {@code tcpip} tears down the transport that sent it, so a
     * close or EOF after the service request is the acknowledgement; the next
     * port probe is the proof.
     */
    public static boolean pinPort(Context context, int port) {
        allowConscryptExporter();
        if (port < 1 || port > 65535) return false;
        final PrivateKey privateKey;
        final X509Certificate certificate;
        try {
            privateKey = loadKey(context);
            certificate = AdbCertificate.loadOrCreate(context);
        } catch (Exception e) {
            Diag.warn(context, "pin.tls_failed", "could not load the app key: " + message(e));
            return false;
        }

        Endpoint advertised = null;
        try {
            advertised = discoverOrNull(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, PIN_DISCOVERY_MS);
        } catch (Throwable e) {
            Diag.warn(context, "pin.tls_failed", "mDNS discovery failed: " + message(e));
        }
        if (advertised != null && tryPin(context, advertised, port, privateKey, certificate)) {
            Diag.info(context, "pin.tls_ok", "asked adbd over TLS to listen on " + port);
            return true;
        }

        // mDNS can be stale or absent at boot. /proc/net/tcp is readable from
        // the app and keeps the fallback to listeners, not a blind 20k-port
        // sweep. The range is Android's ephemeral range; a wrong candidate
        // fails the TLS handshake and the next one is tried.
        List<Endpoint> scanned = scanLocalPorts();
        for (int i = 0; i < scanned.size() && i < MAX_SCAN_CANDIDATES; i++) {
            if (tryPin(context, scanned.get(i), port, privateKey, certificate)) {
                Diag.info(context, "pin.tls_ok", "asked adbd over TLS to listen on " + port
                        + " via " + scanned.get(i).port);
                return true;
            }
        }
        Diag.warn(context, "pin.tls_failed", "no in-app TLS endpoint could pin port " + port);
        return false;
    }

    private static boolean tryPin(final Context context, final Endpoint endpoint, final int port,
                                  final PrivateKey privateKey, final X509Certificate certificate) {
        final AtomicReference<Closeable> live = new AtomicReference<>();
        try {
            within(PIN_MS, live, new Attempt<Void>() {
                @Override
                public Void run() throws Exception {
                    AdbConnection connection = AdbConnection.create(
                            endpoint.address.getHostAddress(), endpoint.port,
                            privateKey, certificate);
                    live.set(connection);
                    try {
                        if (!connection.connect(PIN_CONNECT_MS, TimeUnit.MILLISECONDS, true)) {
                            throw new IOException(noAnswer(PIN_CONNECT_MS));
                        }
                        try {
                            AdbStream stream = connection.open("tcpip:" + port);
                            live.set(stream);
                            try {
                                stream.close();
                            } catch (Exception ignored) {
                                // The service has already been sent.
                            }
                        } catch (IOException e) {
                            // adbd acknowledges tcpip by restarting and closing
                            // this transport; that is success, not a failure.
                            if (!isTransportTeardown(e)) throw e;
                        }
                    } finally {
                        try {
                            connection.close();
                        } catch (Exception ignored) {
                            // Already torn down by adbd.
                        }
                    }
                    return null;
                }
            });
            return true;
        } catch (TimeoutException e) {
            Diag.warn(context, "pin.tls_timeout", noAnswer(PIN_MS));
        } catch (AdbAuthenticationFailedException e) {
            Diag.warn(context, "pin.tls_untrusted",
                    "the phone does not trust this app's key; pair again");
        } catch (Exception e) {
            Diag.warn(context, "pin.tls_failed", message(e));
        }
        return false;
    }

    private static boolean isTransportTeardown(IOException e) {
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("Stream closed")
                || message.contains("Connection reset")
                || message.contains("Broken pipe")
                || message.contains("Socket closed");
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
        Endpoint endpoint = discoverOrNull(context, serviceType, DISCOVERY_MS);
        if (endpoint == null) {
            throw new IOException(failure);
        }
        return endpoint;
    }

    private static Endpoint discoverOrNull(final Context context, String serviceType, long timeoutMs) {
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
            found.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mdns.stop();
        }
        return result.get();
    }

    /** Listening local ports in Android's ephemeral range, as a stale-mDNS fallback. */
    private static List<Endpoint> scanLocalPorts() {
        Set<Integer> ports = new LinkedHashSet<>();
        readLocalPorts("/proc/net/tcp", ports);
        readLocalPorts("/proc/net/tcp6", ports);
        List<Endpoint> endpoints = new ArrayList<>();
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            for (Integer port : ports) endpoints.add(new Endpoint(loopback, port));
        } catch (Exception ignored) {
            // No loopback address means no fallback; mDNS remains the path.
        }
        return endpoints;
    }

    private static void readLocalPorts(String path, Set<Integer> ports) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 4 || !"0A".equals(fields[3])) continue;
                int colon = fields[1].lastIndexOf(':');
                if (colon < 0) continue;
                try {
                    int port = Integer.parseInt(fields[1].substring(colon + 1), 16);
                    if (port >= SCAN_PORT_START && port <= SCAN_PORT_END) ports.add(port);
                } catch (NumberFormatException ignored) {
                    // A malformed proc line is not a candidate.
                }
            }
        } catch (Exception ignored) {
            // /proc/net may be restricted on some builds; fall back to mDNS only.
        }
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.isEmpty() ? t.getClass().getSimpleName() : text;
    }
}

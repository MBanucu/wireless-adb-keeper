package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private Pairing() {}

    /** Pairs with this phone using the code the system dialog is showing. */
    public static String pair(Context context, String rawCode) {
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) {
            return "Enter the 6-digit code shown in Developer options";
        }
        try {
            Endpoint endpoint = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                    "Wireless debugging is not showing a pairing code. Open Developer options, "
                            + "tap \"Pair device with pairing code\", then try again.");
            PrivateKey privateKey = AdbIdentity.load(context).privateKey();
            X509Certificate certificate = AdbCertificate.loadOrCreate(context);

            PairingConnectionCtx pairing = new PairingConnectionCtx(
                    endpoint.address.getHostAddress(), endpoint.port,
                    code.getBytes(ASCII), privateKey, certificate, "pairing");
            try {
                pairing.start();
            } finally {
                pairing.close();
            }
            return "Paired. The app's key is now trusted by this phone.";
        } catch (IOException e) {
            return "Pairing failed: " + message(e);
        } catch (Exception e) {
            return "Pairing failed: " + message(e);
        }
    }

    /**
     * Opens a shell over the wireless-debugging TLS port with the app's key.
     * This is what proves the pairing worked: adbd only answers once it trusts
     * the key that was presented.
     */
    public static String verify(Context context) {
        try {
            Endpoint endpoint = discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT,
                    "Wireless debugging is off. Turn it on in Developer options, then try again.");
            PrivateKey privateKey = AdbIdentity.load(context).privateKey();
            X509Certificate certificate = AdbCertificate.loadOrCreate(context);

            AdbConnection connection = AdbConnection.create(
                    endpoint.address.getHostAddress(), endpoint.port,
                    privateKey, certificate);
            try {
                connection.connect();
                AdbStream stream = connection.open("shell:id");
                byte[] buffer = new byte[256];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = stream.read(buffer, 0, buffer.length)) > 0) {
                    out.write(buffer, 0, n);
                }
                String reply = new String(out.toByteArray(), Charset.forName("UTF-8")).trim();
                return reply.isEmpty()
                        ? "Connected, but the shell said nothing back."
                        : "Connected as " + reply;
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            return "No connection yet: " + message(e);
        }
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

package io.github.mbanucu.wirelessadbkeeper;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Host-side probe for {@link AdbClient}: runs it from a JVM against adbd on the
 * loopback with a key that is already trusted.
 *
 * This exists because the app cannot yet hold a trusted key (M2 owns that), so
 * the protocol code is verified from Termux, where `~/.android/adbkey` is the
 * key adbd already knows. Not part of the APK: `build.sh` compiles `src/` only.
 *
 * <pre>
 * javac -d /tmp/probe src/.../AdbClient.java src/.../AdbKey.java tools/AdbProbe.java
 * java -cp /tmp/probe io.github.mbanucu.wirelessadbkeeper.AdbProbe ~/.android/adbkey
 * java -cp /tmp/probe ... AdbProbe ~/.android/adbkey [port] [pinPort] --tcpip
 * </pre>
 *
 * {@code --tcpip} sends {@code tcpip:<pinPort>} (5555 by default) and times how
 * long until a fresh authenticated connection to that port works. That is the
 * number that matters for the app's re-pin path, and it is deliberately measured
 * by reconnecting rather than by watching the old socket: during an adbd restart
 * the previous listener can still accept TCP for a moment before the new one is
 * ready. The initial {@code port} may be the TLS port; the pinned port is a
 * separate argument because it is the one being measured.
 */
public final class AdbProbe {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        boolean tcpip = false;
        java.util.List<String> positional = new java.util.ArrayList<>();
        for (String arg : args) {
            if ("--tcpip".equals(arg)) {
                tcpip = true;
            } else {
                positional.add(arg);
            }
        }
        String keyPath = positional.size() > 0
                ? positional.get(0)
                : System.getProperty("user.home") + "/.android/adbkey";
        int port = positional.size() > 1 ? Integer.parseInt(positional.get(1)) : 5555;
        int pinPort = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 5555;

        AdbKey key = AdbKey.fromPkcs8Pem(new String(Files.readAllBytes(Paths.get(keyPath)), UTF8));
        System.out.println("key      " + key.publicKeyBase64().substring(0, 16) + "... ("
                + key.publicKeyBlob().length + " bytes)");

        // The encoder is only right if it reproduces adb's own output.
        String published = new String(Files.readAllBytes(Paths.get(keyPath + ".pub")), UTF8);
        String expected = published.trim().split("\\s+")[0];
        boolean pubkeyMatches = expected.equals(key.publicKeyBase64());
        System.out.println("pubkey   " + (pubkeyMatches
                ? "matches " + keyPath + ".pub"
                : "DIFFERS from " + keyPath + ".pub"));

        long start = System.currentTimeMillis();
        try (AdbClient client = AdbClient.connect("127.0.0.1", port, key, 4000)) {
            System.out.println("handshake " + (System.currentTimeMillis() - start) + " ms, "
                    + client.banner());
            System.out.println("id       " + client.shell("id").trim());
            System.out.println("getprop  " + client.shell("getprop ro.build.version.release").trim());
            System.out.println("echo     " + client.shell("echo probe-ok").trim());
        }
        if (tcpip) {
            repin(key, pinPort);
        }
        if (!pubkeyMatches) {
            // A mismatch means the public-key encoder is wrong, even though the
            // private key still authenticates. Fail the probe so it cannot be
            // mistaken for a pass in a script.
            throw new IllegalStateException("encoded public key differs from " + keyPath + ".pub");
        }
    }

    /** Sends {@code tcpip} and measures until the pinned port authenticates again. */
    private static void repin(AdbKey key, int port) throws Exception {
        System.out.println("re-pin   sending tcpip:" + port);
        long start = System.nanoTime();
        try (AdbClient client = AdbClient.connect("127.0.0.1", port, key, 4000)) {
            try {
                client.tcpip(port);
            } catch (java.io.IOException expected) {
                // adbd tears the transport down to switch modes; that is the ack.
                System.out.println("re-pin   transport closed as expected: " + expected.getMessage());
            }
        }

        int attempts = 0;
        while (attempts < 80) {
            attempts++;
            try (AdbClient client = AdbClient.connect("127.0.0.1", port, key, 700)) {
                String id = client.shell("id").trim();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                System.out.println("repinned " + elapsed + " ms after " + attempts
                        + " attempt(s), authed as " + id.split(" ")[0]);
                System.out.println("prop     service.adb.tcp.port="
                        + client.shell("getprop service.adb.tcp.port").trim());
                return;
            } catch (java.io.IOException notYet) {
                Thread.sleep(25);
            }
        }
        throw new java.io.IOException("5555 did not come back after " + attempts + " attempts");
    }
}

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
 * </pre>
 */
public final class AdbProbe {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        String keyPath = args.length > 0
                ? args[0]
                : System.getProperty("user.home") + "/.android/adbkey";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;

        AdbKey key = AdbKey.fromPkcs8Pem(new String(Files.readAllBytes(Paths.get(keyPath)), UTF8));
        System.out.println("key      " + key.publicKeyBase64().substring(0, 16) + "... ("
                + key.publicKeyBlob().length + " bytes)");

        // The encoder is only right if it reproduces adb's own output.
        String published = new String(Files.readAllBytes(Paths.get(keyPath + ".pub")), UTF8);
        String expected = published.trim().split("\\s+")[0];
        System.out.println("pubkey   " + (expected.equals(key.publicKeyBase64())
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
    }
}

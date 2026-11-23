package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;

/**
 * The app's own ADB identity: one RSA key, generated on first use and kept in
 * device-protected storage.
 *
 * Device-protected storage is the whole point. `PinnedPort` works at
 * `LOCKED_BOOT_COMPLETED`, before the first unlock, and credential-encrypted
 * storage is unreadable there — a key kept in the ordinary `getFilesDir()`
 * would make re-pinning fail on exactly the reboot it exists for. So the file
 * lives under `createDeviceProtectedStorageContext()`.
 *
 * The private key is stored as raw PKCS#8 in a 0600 file rather than in
 * AndroidKeyStore, because ADB authentication needs the key material (its own
 * signature padding, and the 524-byte public key blob), not a Keystore handle.
 *
 * A missing key is generated; an unreadable one is reported, not replaced.
 * Regenerating would be silent and would throw away the pairing that makes the
 * key useful, so the failure has to be visible instead.
 */
public final class AdbIdentity {
    static final String DIR = "adb-identity";
    static final String KEY_FILE = "adbkey.pk8";
    /** Name adbd records next to the key in adb_keys. */
    static final String KEY_NAME = "wireless-adb-keeper";

    private static volatile AdbKey cached;

    private AdbIdentity() {}

    /** The device-protected directory holding the key. */
    public static File dir(Context c) {
        Context direct = c.createDeviceProtectedStorageContext();
        return new File(direct.getFilesDir(), DIR);
    }

    /** Loads the key, generating it on first use. Safe from any thread. */
    public static AdbKey load(Context c) throws IOException {
        File dir = dir(c);
        File file = new File(dir, KEY_FILE);
        AdbKey key = cached;
        // A deleted key is the documented way to reset the identity; do not keep
        // serving the in-memory copy after the file it came from is gone.
        if (key != null && file.isFile()) {
            return key;
        }
        synchronized (AdbIdentity.class) {
            key = cached;
            if (key == null || !file.isFile()) {
                key = loadOrCreate(dir);
                cached = key;
            }
        }
        return key;
    }

    /** As {@link #load(Context)}, for the host-side probe. */
    static AdbKey loadOrCreate(File dir) throws IOException {
        File file = new File(dir, KEY_FILE);
        if (file.isFile()) {
            return read(file);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        restrict(dir, true);
        KeyPair pair = generate();
        PrivateKey privateKey = pair.getPrivate();
        AdbKey key = AdbKey.of(privateKey);
        write(file, privateKey.getEncoded());
        return key;
    }

    static AdbKey read(File file) throws IOException {
        byte[] der = readAll(file);
        try {
            return AdbKey.fromPkcs8(der);
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable ADB key at " + file + " ("
                    + e.getMessage() + "); delete it to generate a new one, then pair again", e);
        }
    }

    /**
     * Writes through a temporary file and renames, so a crash mid-write cannot
     * leave a half key where the real one was.
     */
    static void write(File file, byte[] der) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(der);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        restrict(tmp, false);
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("could not move " + tmp + " to " + file);
        }
        restrict(file, false);
    }

    /** Owner-only permissions; the key is the app's identity. */
    private static void restrict(File file, boolean directory) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }

    private static KeyPair generate() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IOException("could not generate an RSA key: " + e, e);
        }
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }
}

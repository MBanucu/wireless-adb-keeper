package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The capability token that lets an ordinary app uid drive the control surface.
 *
 * <p>The token is 32 random bytes in a 0600 file next to the ADB key, in
 * device-protected storage. It is minted only by the shell-only "token" method,
 * never as a side effect of an ordinary request, so an app that was not given
 * the token cannot make one appear.
 *
 * <p>The file stays raw bytes; the shell and the Termux file carry 64 lowercase
 * hex characters. Comparisons go through {@link MessageDigest#isEqual}: the
 * expected value is the file, the presented value is decoded hex, and a
 * malformed guess is compared as a dummy of the same length. That keeps a
 * wrong guess from learning anything about the real token beyond "no".
 */
public final class ControlToken {
    static final String FILE = "control.token";
    static final int BYTES = 32;
    private static final int HEX = BYTES * 2;

    private ControlToken() {}

    /** The token file, in the same device-protected directory as the ADB key. */
    public static File file(Context c) {
        return new File(AdbIdentity.dir(c), FILE);
    }

    /** Returns the token as hex, minting it on first use. */
    public static String issue(Context c) throws IOException {
        return loadOrCreate(AdbIdentity.dir(c));
    }

    /** True if {@code presented} is the stored token. Never mints. */
    public static boolean accept(Context c, String presented) {
        byte[] expected = null;
        File file = file(c);
        if (file.isFile()) {
            try {
                expected = read(file);
            } catch (IOException ignored) {
                // An unreadable token is no token: refuse. Putting the path or
                // the failure into the caller's reply would be a hint about
                // whether a token exists at all.
            }
        }
        return matches(expected, presented);
    }

    /** As {@link #issue(Context)}, for the host-side probe. */
    static String loadOrCreate(File dir) throws IOException {
        synchronized (ControlToken.class) {
            File file = new File(dir, FILE);
            if (file.exists()) {
                byte[] existing = read(file);
                if (existing.length != BYTES) {
                    // A short or empty file is a broken token, not a missing
                    // one. Replacing it would silently invalidate the copy that
                    // Termux installed, so report it and leave it alone.
                    throw new IOException("unreadable control token at " + file
                            + " (" + existing.length + " bytes, expected " + BYTES
                            + "); delete it to mint a new one");
                }
                return toHex(existing);
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("could not create " + dir);
            }
            AdbIdentity.restrict(dir, true);
            byte[] raw = new byte[BYTES];
            new SecureRandom().nextBytes(raw);
            AdbIdentity.write(file, raw);
            return toHex(raw);
        }
    }

    static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    static boolean matches(byte[] expected, String presented) {
        byte[] candidate = decodeHex(presented);
        byte[] safe = candidate;
        if (safe == null) {
            safe = new byte[expected == null ? 0 : expected.length];
        }
        byte[] wanted = expected == null ? new byte[0] : expected;
        boolean equal = MessageDigest.isEqual(wanted, safe);
        return expected != null && candidate != null && equal;
    }

    private static byte[] decodeHex(String text) {
        if (text == null || text.length() != HEX) {
            return null;
        }
        byte[] out = new byte[BYTES];
        for (int i = 0; i < BYTES; i++) {
            int hi = nibble(text.charAt(i * 2));
            int lo = nibble(text.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int nibble(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        return -1;
    }

    private static String toHex(byte[] raw) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }
}

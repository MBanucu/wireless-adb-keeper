package io.github.mbanucu.wirelessadbkeeper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Set;

import javax.crypto.Cipher;

/**
 * Host-side probe for {@link AdbIdentity}: creates a key in a directory, reloads
 * it, and checks that the stored bytes are a usable ADB identity.
 *
 * The storage path itself (device-protected) needs Android, so it is verified on
 * the phone; what is checked here is everything that can be checked without it:
 * the file is 0600, a reload returns the same key, and the signature is the ADB
 * shape (PKCS#1 `DigestInfo` carrying the token, not a hash of it).
 */
public final class IdentityProbe {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/tmp/adb-identity-probe");
        deleteTree(dir);
        int failures = 0;

        AdbKey first = AdbIdentity.loadOrCreate(dir);
        File keyFile = new File(dir, AdbIdentity.KEY_FILE);
        System.out.println("file      " + keyFile + " exists=" + keyFile.isFile()
                + " bytes=" + keyFile.length());
        failures += check("key file is 0600", perms(keyFile).equals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        failures += check("public key is 524 bytes", first.publicKeyBlob().length == 524);
        failures += check("public key line names the key",
                first.publicKeyLine(AdbIdentity.KEY_NAME).endsWith(" " + AdbIdentity.KEY_NAME));

        AdbKey second = AdbIdentity.loadOrCreate(dir);
        failures += check("reload keeps the same key",
                second.publicKeyBase64().equals(first.publicKeyBase64()));

        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (i * 7 + 1);
        }
        byte[] signature = second.sign(token);
        failures += check("signature is one RSA block", signature.length == 256);
        failures += check("signature carries the token as the digest",
                Arrays.equals(recover(signature, second), digestInfo(token)));
        failures += check("a 19-byte token is refused", throwsOnShortToken(second));

        System.out.println(failures == 0 ? "OK" : failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** PKCS#1 v1.5 "encrypt with the private key": what a verifier undoes. */
    private static byte[] recover(byte[] signature, AdbKey key) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(modulus(key), java.math.BigInteger.valueOf(65537)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, publicKey);
        return rsa.doFinal(signature);
    }

    private static java.math.BigInteger modulus(AdbKey key) {
        byte[] blob = key.publicKeyBlob();
        byte[] big = new byte[64 * 4 + 1];
        big[0] = 0; // keep it positive
        for (int i = 0; i < 64; i++) {
            int word = (blob[8 + i * 4] & 0xff)
                    | (blob[9 + i * 4] & 0xff) << 8
                    | (blob[10 + i * 4] & 0xff) << 16
                    | (blob[11 + i * 4] & 0xff) << 24;
            int offset = 1 + (63 - i) * 4;
            big[offset] = (byte) (word >>> 24);
            big[offset + 1] = (byte) (word >>> 16);
            big[offset + 2] = (byte) (word >>> 8);
            big[offset + 3] = (byte) word;
        }
        return new java.math.BigInteger(big);
    }

    private static byte[] digestInfo(byte[] token) {
        byte[] prefix = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        };
        byte[] out = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(token, 0, out, prefix.length, token.length);
        return out;
    }

    private static boolean throwsOnShortToken(AdbKey key) {
        try {
            key.sign(new byte[19]);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static Set<PosixFilePermission> perms(File file) throws Exception {
        return Files.getPosixFilePermissions(Paths.get(file.getAbsolutePath()));
    }

    private static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}

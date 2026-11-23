package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkThrows;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The host-checkable half of the app's ADB identity: the key file is 0600, a
 * reload keeps the key, and the signature is the PKCS#1 shape adbd expects.
 * The device-protected directory itself needs a phone (#55).
 */
public final class IdentityTest {

    private IdentityTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("wak-identity-");
        File dir = temp.toFile();
        try {
            deleteTree(dir);
            AdbKey first = AdbIdentity.loadOrCreate(dir);
            File keyFile = new File(dir, AdbIdentity.KEY_FILE);

            checkTrue("the key file is created", keyFile.isFile());
            check("the key file is 0600", Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(Paths.get(keyFile.getAbsolutePath())));
            check("the public key blob is 524 bytes", 524, first.publicKeyBlob().length);
            checkTrue("the public key line names the key",
                    first.publicKeyLine(AdbIdentity.KEY_NAME).endsWith(" " + AdbIdentity.KEY_NAME));

            AdbKey second = AdbIdentity.loadOrCreate(dir);
            check("a reload keeps the same public key",
                    first.publicKeyBase64(), second.publicKeyBase64());

            byte[] token = token();
            byte[] signature = second.sign(token);
            check("a signature is one RSA block", 256, signature.length);
            checkTrue("the signature carries the token as the digest",
                    Arrays.equals(digestInfo(token), recover(signature, second)));
            checkThrows("a 19-byte token is refused", IllegalArgumentException.class,
                    () -> second.sign(new byte[19]));
        } finally {
            deleteTree(dir);
            Files.deleteIfExists(temp);
        }

        report("IdentityTest");
    }

    private static byte[] token() {
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (i * 7 + 1);
        }
        return token;
    }

    /** PKCS#1 v1.5 "encrypt with the private key": what a verifier undoes. */
    private static byte[] recover(byte[] signature, AdbKey key) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(modulus(key), BigInteger.valueOf(65537)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, publicKey);
        return rsa.doFinal(signature);
    }

    private static BigInteger modulus(AdbKey key) {
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
        return new BigInteger(big);
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

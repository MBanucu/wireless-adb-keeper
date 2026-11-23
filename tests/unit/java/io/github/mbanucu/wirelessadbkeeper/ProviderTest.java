package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * AdbKey.sign against the platform's own providers: the signature must unpack
 * to the DigestInfo adb specifies, NONEwithRSA must accept the block, and
 * SHA1withRSA must reject it because that hashes the token again.
 */
public final class ProviderTest {

    private ProviderTest() {
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        AdbKey key = AdbKey.of(pair.getPrivate());

        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (i * 11 + 3);
        }
        byte[] signature = key.sign(token);
        check("a signature is 256 bytes", 256, signature.length);

        // What the peer does: public-key "decrypt" with PKCS#1 v1.5 padding.
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(modulus(key), BigInteger.valueOf(65537)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, pub);
        byte[] recovered = rsa.doFinal(signature);
        checkTrue("the provider unpacks the DigestInfo adb expects",
                Arrays.equals(digestInfo(token), recovered));

        // The same block must satisfy a PKCS#1 v1.5 verifier for that digest.
        Signature verifier = Signature.getInstance("NONEwithRSA");
        verifier.initVerify(pub);
        verifier.update(digestInfo(token));
        checkTrue("NONEwithRSA accepts the signature over the DigestInfo",
                verifier.verify(signature));

        Signature sha1 = Signature.getInstance("SHA1withRSA");
        sha1.initVerify(pub);
        sha1.update(token);
        checkTrue("SHA1withRSA over the token is rejected (it hashes again)",
                !sha1.verify(signature));

        report("ProviderTest");
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

    private static BigInteger modulus(AdbKey key) {
        byte[] blob = key.publicKeyBlob();
        byte[] big = new byte[64 * 4 + 1];
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
}

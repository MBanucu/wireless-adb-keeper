package io.github.mbanucu.wirelessadbkeeper;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * An ADB identity: the RSA key adbd authorises, plus the encodings the protocol
 * needs.
 *
 * The private key never leaves the app; what travels is the public key blob and
 * a signature over the token adbd sends. The blob is Android's own
 * {@code RSAPublicKey} layout (524 bytes for 2048-bit keys: word count, n0inv,
 * modulus, rr, exponent), base64'd — not X.509, which is why this is built by
 * hand instead of asking the JDK.
 *
 * Only PKCS#8 private keys are accepted, because that is what
 * {@link KeyFactory} can read without a third-party ASN.1 parser. M2 writes the
 * key in that form; the Termux test key happens to be PKCS#8 already, which is
 * what lets the host-side probe reuse it.
 */
public final class AdbKey {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final BigInteger TWO_32 = BigInteger.ONE.shiftLeft(32);
    private static final BigInteger TWO_2048 = BigInteger.ONE.shiftLeft(2048);

    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final String publicKeyBase64;

    private AdbKey(PrivateKey privateKey, byte[] publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.publicKeyBase64 = Base64Codec.encode(publicKey);
    }

    /** Reads an unencrypted PKCS#8 PEM block. */
    public static AdbKey fromPkcs8Pem(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return fromPkcs8(Base64Codec.decode(body));
    }

    /** Reads the DER of an unencrypted PKCS#8 private key. */
    public static AdbKey fromPkcs8(byte[] der) {
        try {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            return of(key);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a PKCS#8 RSA key: " + e, e);
        }
    }

    /** Wraps an RSA private key that has its CRT parameters available. */
    public static AdbKey of(PrivateKey key) {
        if (!(key instanceof RSAPrivateCrtKey)) {
            throw new IllegalArgumentException("need an RSAPrivateCrtKey, got " + key.getAlgorithm());
        }
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) key;
        return new AdbKey(key, encodePublicKey(rsa.getModulus(), rsa.getPublicExponent()));
    }

    /** The key itself, for the TLS layer that pairing builds on. */
    PrivateKey privateKey() {
        return privateKey;
    }

    /** The 524-byte Android public key structure. */
    private static byte[] encodePublicKey(BigInteger n, BigInteger e) {
        BigInteger modulus = n;
        if (modulus.bitLength() != 2048) {
            throw new IllegalArgumentException("adbd only accepts 2048-bit keys, got "
                    + modulus.bitLength());
        }
        byte[] out = new byte[524];
        int words = 64;
        putInt(out, 0, words);
        // n0inv = -1 / n[0] mod 2^32, the Montgomery constant adbd recomputes.
        BigInteger n0 = modulus.and(TWO_32.subtract(BigInteger.ONE));
        BigInteger n0inv = n0.modInverse(TWO_32).negate().mod(TWO_32);
        putInt(out, 4, n0inv.intValue());
        // Modulus and rr are little-endian 32-bit words, least significant first.
        putWords(out, 8, modulus, words);
        BigInteger rr = TWO_2048.multiply(TWO_2048).mod(modulus);
        putWords(out, 8 + words * 4, rr, words);
        putInt(out, 8 + words * 8, e.intValue());
        return out;
    }

    private static void putWords(byte[] out, int offset, BigInteger value, int words) {
        BigInteger rest = value;
        BigInteger mask = TWO_32.subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            putInt(out, offset + i * 4, rest.and(mask).intValue());
            rest = rest.shiftRight(32);
        }
    }

    private static void putInt(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    /** The key as adbd wants it in an {@code AUTH} packet: base64, NUL-terminated. */
    public byte[] publicKeyPayload() {
        byte[] b64 = publicKeyBase64.getBytes(ASCII);
        byte[] payload = new byte[b64.length + 1];
        System.arraycopy(b64, 0, payload, 0, b64.length);
        return payload;
    }

    /** The {@code adb_keys} line for this key, {@code <base64> <name>}. */
    public String publicKeyLine(String name) {
        return publicKeyBase64 + " " + name;
    }

    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    byte[] publicKeyBlob() {
        return publicKey.clone();
    }

    /**
     * Signs an {@code AUTH} token.
     *
     * The token goes into the PKCS#1 v1.5 DigestInfo <em>as the digest</em>; it is
     * not hashed again. That looks wrong next to {@code SHA1withRSA} — and using
     * the JDK's {@code SHA1withRSA} here is rejected by adbd — but it is what
     * adb's own client produces: captured from adb 35.x, the 256-byte signature
     * is byte-for-byte {@code RSA_private_encrypt(DigestInfo(prefix || token))}.
     * Because the token is 20 random bytes, using it as the digest is equivalent
     * in strength to hashing it.
     */
    public byte[] sign(byte[] token) {
        if (token.length != 20) {
            throw new IllegalArgumentException("ADB auth tokens are 20 bytes, got " + token.length);
        }
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, token.length);
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, privateKey);
            return rsa.doFinal(digestInfo);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the auth token: " + e, e);
        }
    }

    /** ASN.1 prefix for a SHA-1 PKCS#1 v1.5 signature: {@code DigestInfo} header. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    };
}

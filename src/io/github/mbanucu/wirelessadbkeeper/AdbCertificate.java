package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The self-signed X.509 certificate that goes with the app's ADB key.
 *
 * Wireless debugging's pairing handshake is TLS-shaped: each side presents a
 * certificate, and adbd keeps the one it paired with. It never checks who
 * signed it — every ADB client self-signs — so the certificate exists to carry
 * the public key, not to prove anything.
 *
 * Built here as DER by hand instead of pulling in BouncyCastle's certificate
 * builder: the structure is fixed and small, and keeping it dependency-free
 * means `build.sh` still compiles every file in `src/` with nothing but
 * android.jar. {@link #loadOrCreate} verifies its own output through the JDK's
 * X.509 parser before trusting it, so a mistake in this encoder fails loudly
 * at generation time rather than as a rejected pairing.
 *
 * The file lives beside the key in device-protected storage, because pairing
 * has to work as soon as the app can run, and because losing it would mean
 * pairing again.
 */
public final class AdbCertificate {
    static final String CERT_FILE = "adbkey.crt";
    private static final String SUBJECT = "wireless-adb-keeper";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_SET = 0x31;
    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_BIT_STRING = 0x03;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int TAG_NULL = 0x05;
    private static final int TAG_OID = 0x06;
    private static final int TAG_UTF8 = 0x0C;
    private static final int TAG_UTC_TIME = 0x17;
    private static final int TAG_GENERALIZED_TIME = 0x18;
    private static final int TAG_BOOLEAN = 0x01;
    private static final int TAG_VERSION = 0xA0;
    private static final int TAG_EXTENSIONS = 0xA3;

    private static volatile X509Certificate cached;

    private AdbCertificate() {}

    /** The certificate for the app's key, generated beside it on first use. */
    public static X509Certificate loadOrCreate(Context c) throws IOException {
        // Deleting the key file is the documented reset, and AdbIdentity then
        // generates a new one immediately. A cached certificate would still name
        // the old key, and a TLS handshake with a certificate that does not match
        // its key fails in a way that reads like a wrong pairing code.
        PrivateKey key = AdbIdentity.load(c).privateKey();
        X509Certificate cert = cached;
        if (cert != null && certifies(cert, key)) {
            return cert;
        }
        synchronized (AdbCertificate.class) {
            cert = cached;
            if (cert == null || !certifies(cert, key)) {
                File file = new File(AdbIdentity.dir(c), CERT_FILE);
                boolean existed = file.isFile();
                boolean reusable = certifiesFile(file, key);
                cert = loadOrCreate(AdbIdentity.dir(c), key);
                cached = cert;
                if (!existed) {
                    Diag.info(c, "key.certificate_created",
                            "created the self-signed certificate beside the ADB key");
                } else if (!reusable) {
                    Diag.warn(c, "key.certificate_regenerated",
                            "the certificate did not match the current ADB key;"
                                    + " regenerated it, pair again");
                }
            }
        }
        return cert;
    }

    /** Whether an existing certificate file belongs to this key. */
    private static boolean certifiesFile(File file, PrivateKey key) {
        if (!file.isFile()) return false;
        try {
            return read(file).getPublicKey().equals(publicKeyOf(key));
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether {@code cert} carries the public half of {@code key}. */
    private static boolean certifies(X509Certificate cert, PrivateKey key) {
        try {
            return cert.getPublicKey().equals(publicKeyOf(key));
        } catch (IOException e) {
            return false;
        }
    }

    /** As {@link #loadOrCreate(Context)}, for the host-side probe. */
    static X509Certificate loadOrCreate(File dir, PrivateKey key) throws IOException {
        File file = new File(dir, CERT_FILE);
        PublicKey publicKey = publicKeyOf(key);
        if (file.isFile()) {
            try {
                X509Certificate existing = read(file);
                // A key that was regenerated must not keep the old key's
                // certificate; the file is derived data, so replacing it is safe.
                if (existing.getPublicKey().equals(publicKey)) {
                    return existing;
                }
            } catch (Exception ignored) {
                // Unreadable or not a certificate: replace it below.
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        byte[] der = generate(key, publicKey);
        write(file, der);
        return read(file);
    }

    private static X509Certificate read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        } catch (Exception e) {
            throw new IOException("unreadable certificate at " + file + ": " + e, e);
        } finally {
            in.close();
        }
    }

    /**
     * Writes through a temporary file and renames, and restricts before the
     * rename so the file is never briefly readable by anyone else.
     */
    private static void write(File file, byte[] der) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(der);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        tmp.setReadable(false, false);
        tmp.setWritable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(true, true);
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("could not move " + tmp + " to " + file);
        }
    }

    /** Builds, signs and re-reads the certificate; the re-read is the check. */
    static byte[] generate(PrivateKey privateKey, PublicKey publicKey) throws IOException {
        return generate(privateKey, publicKey, System.currentTimeMillis());
    }

    /** As above, with an explicit clock so the host probe can reach 2050. */
    static byte[] generate(PrivateKey privateKey, PublicKey publicKey, long now) throws IOException {
        try {
            byte[] serialBytes = new byte[8];
            new SecureRandom().nextBytes(serialBytes);
            serialBytes[0] &= 0x7F;                 // positive, and short enough to stay 8 bytes
            if (serialBytes[0] == 0) serialBytes[0] = 0x01;
            BigInteger serial = new BigInteger(1, serialBytes);

            byte[] algorithm = der(TAG_SEQUENCE, oid(1, 2, 840, 113549, 1, 1, 11), der(TAG_NULL));
            byte[] name = der(TAG_SEQUENCE,
                    der(TAG_SET, der(TAG_SEQUENCE,
                            oid(2, 5, 4, 3), der(TAG_UTF8, SUBJECT.getBytes(UTF_8)))));

            // A day back avoids "not yet valid" on a clock that drifted; 20
            // years forward keeps the file out of the way of any renewal logic.
            byte[] validity = der(TAG_SEQUENCE,
                    time(new Date(now - 24L * 60 * 60 * 1000)),
                    time(new Date(now + 20L * 365 * 24 * 60 * 60 * 1000)));

            byte[] extensions = der(TAG_EXTENSIONS, der(TAG_SEQUENCE,
                    extension(oid(2, 5, 29, 19), true, der(TAG_SEQUENCE)),
                    extension(oid(2, 5, 29, 15), true,
                            der(TAG_BIT_STRING, new byte[]{0x07, (byte) 0x80}))));

            byte[] tbs = der(TAG_SEQUENCE,
                    der(TAG_VERSION, der(TAG_INTEGER, new byte[]{0x02})), // v3
                    der(TAG_INTEGER, serial.toByteArray()),
                    algorithm,
                    name,
                    validity,
                    name,
                    publicKey.getEncoded(),                 // SubjectPublicKeyInfo
                    extensions);

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(tbs);
            byte[] signature = signer.sign();

            byte[] cert = der(TAG_SEQUENCE, tbs, algorithm,
                    der(TAG_BIT_STRING, new byte[]{0x00}, signature));

            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(cert));
            parsed.verify(publicKey);
            if (!parsed.getPublicKey().equals(publicKey)) {
                throw new IllegalStateException("certificate does not carry the ADB key");
            }
            return cert;
        } catch (Exception e) {
            throw new IOException("could not build a certificate: " + e, e);
        }
    }

    private static byte[] extension(byte[] oid, boolean critical, byte[] value) {
        return critical
                ? der(TAG_SEQUENCE, oid, der(TAG_BOOLEAN, new byte[]{(byte) 0xFF}),
                        der(TAG_OCTET_STRING, value))
                : der(TAG_SEQUENCE, oid, der(TAG_OCTET_STRING, value));
    }

    /**
     * Encodes a validity endpoint with the tag DER requires for its year.
     *
     * UTCTime is a two-digit year defined only for 1950-2049, so a 20-year
     * notAfter crosses out of it in 2030. GeneralizedTime carries four digits
     * and is valid outside that range, so the tag is picked per value instead
     * of shortening the window: the long validity is what keeps renewal out of
     * the pairing path, and both tags are legal in a v3 certificate.
     */
    private static byte[] time(Date date) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);
        boolean generalized = year < 1950 || year > 2049;
        SimpleDateFormat format = new SimpleDateFormat(
                generalized ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return der(generalized ? TAG_GENERALIZED_TIME : TAG_UTC_TIME,
                format.format(date).getBytes(UTF_8));
    }

    static PublicKey publicKeyOf(PrivateKey privateKey) throws IOException {
        if (!(privateKey instanceof RSAPrivateCrtKey)) {
            throw new IOException("need an RSA key with CRT parameters, got "
                    + privateKey.getAlgorithm());
        }
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privateKey;
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        } catch (Exception e) {
            throw new IOException("could not derive the public key: " + e, e);
        }
    }

    // --- a minimal DER writer: enough for the one structure above ---

    private static byte[] der(int tag, byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 6);
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = 0;
            for (int v = length; v > 0; v >>>= 8) bytes++;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((length >>> (8 * i)) & 0xFF);
        }
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int value = arcs[i];
            byte[] chunk = new byte[5];
            int n = 0;
            do {
                chunk[n++] = (byte) (value & 0x7F);
                value >>>= 7;
            } while (value != 0);
            for (int j = n - 1; j >= 0; j--) {
                body.write(chunk[j] | (j == 0 ? 0 : 0x80));
            }
        }
        return der(TAG_OID, body.toByteArray());
    }
}

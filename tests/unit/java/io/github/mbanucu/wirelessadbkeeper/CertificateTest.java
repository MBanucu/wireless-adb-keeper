package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The self-signed certificate's validity encoding at fixed clocks either side
 * of 2050. The bug only appears when the 20-year window crosses UTCTime's
 * 1950-2049 range, which no one can wait for; an explicit clock reaches it.
 */
public final class CertificateTest {

    private static final SimpleDateFormat ISO =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    static {
        ISO.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    private CertificateTest() {
    }

    public static void main(String[] args) throws Exception {
        String[] clocks = {
                "2025-06-01T12:00:00Z", // both ends inside UTCTime
                "2031-06-01T12:00:00Z", // notAfter past 2049: the reported bug
                "2049-06-01T12:00:00Z", // notBefore UTCTime, notAfter GeneralizedTime
                "2050-01-01T00:00:00Z", // both ends GeneralizedTime
        };

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        for (String clock : clocks) {
            long now = ISO.parse(clock).getTime();
            byte[] der = AdbCertificate.generate(pair.getPrivate(), pair.getPublic(), now);
            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            String[] encoded = validity(der);
            Date notBefore = parsed.getNotBefore();
            Date notAfter = parsed.getNotAfter();

            check(clock + ": notBefore is a day before the clock",
                    now - 24L * 60 * 60 * 1000, notBefore.getTime());
            check(clock + ": notAfter is the 20-year (7300-day) window",
                    now + 20L * 365 * 24 * 60 * 60 * 1000, notAfter.getTime());
            check(clock + ": the notBefore tag matches its year",
                    tagName(notBefore), tag(encoded[0]));
            check(clock + ": the notAfter tag matches its year",
                    tagName(notAfter), tag(encoded[1]));
            check(clock + ": v3, SHA256withRSA, 2048-bit, CA:false", true,
                    parsed.getVersion() == 3
                            && "SHA256withRSA".equals(parsed.getSigAlgName())
                            && ((java.security.interfaces.RSAPublicKey) parsed.getPublicKey())
                                    .getModulus().bitLength() == 2048
                            && parsed.getBasicConstraints() == -1);
        }

        report("CertificateTest");
    }

    private static String tag(String encoded) {
        int space = encoded.indexOf(' ');
        return space < 0 ? encoded : encoded.substring(0, space);
    }

    /** The validity field's two encoded time values, tag name and text. */
    private static String[] validity(byte[] der) {
        int[] certificate = tlv(der, 0);
        int[] tbs = tlv(der, certificate[1]);
        int offset = tbs[1];
        for (int i = 0; i < 4; i++) {
            offset = tlv(der, offset)[2]; // version, serial, signature, issuer
        }
        int[] validity = tlv(der, offset);
        String[] out = new String[2];
        offset = validity[1];
        for (int i = 0; i < 2; i++) {
            int[] value = tlv(der, offset);
            out[i] = tagName(value[0]) + " "
                    + new String(der, value[1], value[2] - value[1], StandardCharsets.US_ASCII);
            offset = value[2];
        }
        return out;
    }

    private static String tagName(Date date) {
        return tagName(year(date) < 1950 || year(date) > 2049 ? 0x18 : 0x17);
    }

    private static String tagName(int tag) {
        return tag == 0x18 ? "GeneralizedTime" : "UTCTime";
    }

    private static int year(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return Integer.parseInt(format.format(date));
    }

    /** {tag, content start, content end} for the DER element at {@code offset}. */
    private static int[] tlv(byte[] der, int offset) {
        int tag = der[offset] & 0xFF;
        int at = offset + 1;
        int length = der[at++] & 0xFF;
        if ((length & 0x80) != 0) {
            int bytes = length & 0x7F;
            length = 0;
            for (int i = 0; i < bytes; i++) {
                length = (length << 8) | (der[at++] & 0xFF);
            }
        }
        return new int[]{tag, at, at + length};
    }
}

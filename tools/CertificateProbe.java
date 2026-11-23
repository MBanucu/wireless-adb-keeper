package io.github.mbanucu.wirelessadbkeeper;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Host-side probe for {@link AdbCertificate}: generates certificates at fixed
 * clocks either side of 2050 and checks the validity encoding and parsed dates.
 *
 * This exists because the bug only shows up when the 20-year window crosses
 * UTCTime's 1950-2049 range, which no one can wait for. The DER is written to a
 * directory so {@code keytool -printcert -file} can check it independently.
 * Not part of the APK: {@code build.sh} compiles {@code src/} only.
 *
 * <pre>
 * javac -d /tmp/probe src/.../AdbCertificate.java tools/CertificateProbe.java
 * java -cp /tmp/probe io.github.mbanucu.wirelessadbkeeper.CertificateProbe /tmp/certs
 * </pre>
 */
public final class CertificateProbe {
    private static final String[] CLOCKS = {
            "2025-06-01T12:00:00Z", // both ends inside UTCTime
            "2031-06-01T12:00:00Z", // notAfter past 2049: the reported bug
            "2049-06-01T12:00:00Z", // notBefore UTCTime, notAfter GeneralizedTime
            "2050-01-01T00:00:00Z", // both ends GeneralizedTime
    };

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/tmp/adb-cert-probe");
        dir.mkdirs();
        int failures = 0;

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("GMT"));

        for (String clock : CLOCKS) {
            long now = iso.parse(clock).getTime();
            byte[] der = AdbCertificate.generate(pair.getPrivate(), pair.getPublic(), now);

            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
            String[] encoded = validity(der);

            Date notBefore = parsed.getNotBefore();
            Date notAfter = parsed.getNotAfter();
            System.out.println("clock      " + clock);
            System.out.println("  notBefore " + iso.format(notBefore) + "  " + encoded[0]);
            System.out.println("  notAfter  " + iso.format(notAfter) + "  " + encoded[1]);

            failures += check("notBefore is a day before the clock",
                    notBefore.getTime() == now - 24L * 60 * 60 * 1000);
            failures += check("notAfter is the 20-year (7300-day) window",
                    notAfter.getTime() == now + 20L * 365 * 24 * 60 * 60 * 1000);
            failures += check("tags match the encoded years",
                    encoded[0].startsWith(tagName(notBefore) + " ")
                            && encoded[1].startsWith(tagName(notAfter) + " "));
            failures += check("v3, SHA256withRSA, 2048-bit, CA:false",
                    parsed.getVersion() == 3
                            && "SHA256withRSA".equals(parsed.getSigAlgName())
                            && parsed.getPublicKey().getEncoded().length > 0
                            && ((java.security.interfaces.RSAPublicKey) parsed.getPublicKey())
                                    .getModulus().bitLength() == 2048
                            && parsed.getBasicConstraints() == -1);

            File out = new File(dir, "cert-" + clock.substring(0, 4) + ".der");
            FileOutputStream stream = new FileOutputStream(out);
            try {
                stream.write(der);
            } finally {
                stream.close();
            }
            System.out.println("  wrote     " + out);
        }

        System.out.println(failures == 0 ? "OK" : failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
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
                    + new String(der, value[1], value[2] - value[1],
                            java.nio.charset.Charset.forName("US-ASCII"));
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

    private static int check(String what, boolean ok) {
        System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkThrows;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The small Base64 codec used for ADB key material and PEM bodies. It is
 * package-private, so this suite lives in the app's package; that is also why
 * the suite is public - SuiteRunner reaches it by reflection.
 */
public final class Base64CodecTest {

    private Base64CodecTest() {
    }

    public static void main(String[] args) {
        check("empty input encodes to nothing", "", Base64Codec.encode(new byte[0]));
        check("hello encodes to its known vector", "aGVsbG8=",
                Base64Codec.encode("hello".getBytes(StandardCharsets.UTF_8)));
        check("one byte pads with two '='", "Zg==",
                Base64Codec.encode(new byte[]{'f'}));
        check("two bytes pad with one '='", "Zm8=",
                Base64Codec.encode(new byte[]{'f', 'o'}));
        check("three bytes need no padding", "Zm9v",
                Base64Codec.encode(new byte[]{'f', 'o', 'o'}));

        check("hello decodes from its known vector", "hello",
                new String(Base64Codec.decode("aGVsbG8="), StandardCharsets.UTF_8));
        check("one byte decodes without padding bytes", 1,
                Base64Codec.decode("Zg==").length);
        check("two bytes decode without padding bytes", 2,
                Base64Codec.decode("Zm8=").length);
        check("three bytes decode", "foo",
                new String(Base64Codec.decode("Zm9v"), StandardCharsets.UTF_8));

        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        checkTrue("every byte value round-trips",
                Arrays.equals(all, Base64Codec.decode(Base64Codec.encode(all))));

        checkThrows("a length that is not a multiple of four is rejected",
                IllegalArgumentException.class, () -> Base64Codec.decode("abc"));
        checkThrows("a character outside the alphabet is rejected",
                IllegalArgumentException.class, () -> Base64Codec.decode("ab!d"));

        report("Base64CodecTest");
    }
}

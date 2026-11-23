package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Journal line formatting, secret scrubbing and the two ring caps. */
public final class DiagFormatTest {

    private DiagFormatTest() {
    }

    public static void main(String[] args) {
        check("a line carries time, uptime, level, code and detail",
                "2026-11-23T17:00:00Z 123s info pin.state listening on 5555",
                DiagFormat.line("2026-11-23T17:00:00Z", 123, "info", "pin.state",
                        "listening on 5555"));
        check("an empty detail leaves no trailing space",
                "2026-11-23T17:00:00Z 1s warn boot.received",
                DiagFormat.line("2026-11-23T17:00:00Z", 1, "warn", "boot.received", ""));
        check("a null detail is the same as an empty one",
                DiagFormat.line("t", 1, "info", "c", ""),
                DiagFormat.line("t", 1, "info", "c", null));

        check("a passphrase value is redacted",
                "wifi passphrase=***",
                DiagFormat.scrub("wifi passphrase=canary-pass"));
        check("a control token value is redacted",
                "ctl token=***",
                DiagFormat.scrub("ctl token=canary-token"));
        check("a pairing code value is redacted",
                "code pairing_code=***",
                DiagFormat.scrub("code pairing_code=canary-pair"));
        check("all three secret shapes in one detail are redacted",
                "passphrase=*** token=*** pairing_code=***",
                DiagFormat.scrub("passphrase=a token=b pairing_code=c"));
        check("the labels are matched without regard to case",
                "passphrase=*** token=*** pairing_code=***",
                DiagFormat.scrub("PASSPHRASE=a TOKEN=b PAIRING_CODE=c"));
        check("a secret after a longer label is still redacted",
                "wifi_passphrase=***",
                DiagFormat.scrub("wifi_passphrase=canary-pass"));
        checkTrue("no secret-shaped value survives a longer label",
                !DiagFormat.scrub("wifi_passphrase=canary-pass").contains("canary"));
        check("newlines become spaces so an entry stays one line",
                "first second third",
                DiagFormat.scrub("first\nsecond\rthird"));
        check("non-ASCII detail is kept as text",
                "Café-网络-📶 ok",
                DiagFormat.scrub("Café-网络-📶 ok"));

        check("both caps keep the newest lines and drop from the front",
                Arrays.asList("three", "four", "five"),
                DiagFormat.trim(Arrays.asList("one", "two", "three", "four", "five"), 3, 1000));
        check("the byte cap counts the newline after every line",
                Arrays.asList("two", "three"),
                DiagFormat.trim(Arrays.asList("one", "two", "three"), 10, 10));
        check("the byte cap can leave only the newest line",
                Arrays.asList("three"),
                DiagFormat.trim(Arrays.asList("one", "two", "three"), 10, 9));
        check("a single line over the byte cap does not empty the journal",
                Arrays.asList("01234567890123456789"),
                DiagFormat.trim(Arrays.asList("01234567890123456789"), 10, 5));
        check("an oversized line is kept only when it is the newest one",
                Arrays.asList("01234567890123456789"),
                DiagFormat.trim(Arrays.asList("old", "01234567890123456789"), 10, 6));
        check("the byte cap counts UTF-8 bytes, not characters",
                Arrays.asList("b"),
                DiagFormat.trim(Arrays.asList("é", "b"), 10, 4));
        checkTrue("trimming does not modify the caller's list",
                trimLeavesInputAlone());
        check("an empty journal stays empty", 0,
                DiagFormat.trim(new ArrayList<String>(), 10, 10).size());

        report("DiagFormatTest");
    }

    private static boolean trimLeavesInputAlone() {
        List<String> input = new ArrayList<>(Arrays.asList("one", "two", "three"));
        DiagFormat.trim(input, 1, 1000);
        return input.equals(Arrays.asList("one", "two", "three"));
    }
}

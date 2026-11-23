package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

/**
 * The {@code RESULT} line the Termux scripts answer with. The Android half -
 * receiving the bundle, the service and the Status page - needs a phone; the
 * parsing and the one-line summaries are pure Java.
 */
public final class TermuxResultTest {

    private TermuxResultTest() {
    }

    public static void main(String[] args) {
        TermuxResult pinned = TermuxResult.parse("pin-adb-port.sh",
                "attaching...\nRESULT pinned=5555\n", "", 0, -1, "", 1L);
        checkTrue("plain success parses", pinned.ok());
        check("success keeps the script's own port", "pinned=5555", pinned.summary());
        check("success exposes the value", "5555", pinned.values.get("pinned"));
        check("success UI line carries stdout and exit code",
                "pinned=5555 (exit 0)", pinned.uiLine());
        checkTrue("a real pin reports the port", pinned.reportsPinned());

        TermuxResult failed = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=no-adbd\n", "adbd: no devices\n", 1, -1, "", 2L);
        checkTrue("an error line is a failure", !failed.ok());
        check("the script's reason is kept", "error=no-adbd", failed.summary());
        check("failure UI line carries the exit code and stderr",
                "error=no-adbd (exit 1); adbd: no devices", failed.uiLine());

        TermuxResult last = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=no-adbd\nmore work\nRESULT pinned=5555\n", "", 0, -1, "", 3L);
        checkTrue("the last RESULT line wins",
                last.ok() && "pinned=5555".equals(last.summary()));

        TermuxResult skipped = TermuxResult.parse("pin-adb-port.sh",
                "RESULT skipped=running\n", "", 0, -1, "", 4L);
        checkTrue("a skipped pin is not an error", skipped.ok());
        checkTrue("a skipped pin does not claim the port is listening",
                !skipped.reportsPinned());

        TermuxResult crlf = TermuxResult.parse("wifi-on.sh",
                "wifi_on=1\r\nRESULT wifi=on\r\n", "", 0, -1, "", 5L);
        checkTrue("CRLF output parses", crlf.ok() && "wifi=on".equals(crlf.summary()));

        TermuxResult nonzero = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", 3, -1, "", 6L);
        checkTrue("a non-zero exit code is a failure", !nonzero.ok());

        TermuxResult internal = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", 0, 1, "Termux service was killed", 7L);
        checkTrue("Termux's own error code is a failure", !internal.ok());

        TermuxResult noExit = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", null, -1, "", 8L);
        checkTrue("a missing exit code is not guessed as success", !noExit.ok());

        TermuxResult silent = TermuxResult.parse("pin-adb-port.sh",
                "attaching...\n", "", 1, -1, "", 9L);
        check("no RESULT line is explained, not accepted", "no RESULT line", silent.summary());
        check("no RESULT line still shows the exit code",
                "no RESULT line (exit 1)", silent.uiLine());

        TermuxResult bare = TermuxResult.parse("wait_adb.sh", "RESULT\n", "", 0, -1, "", 10L);
        checkTrue("a bare RESULT still parses", bare.ok() && "RESULT".equals(bare.summary()));

        TermuxResult prefixed = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\nRESULTX pinned=6666\n", "", 0, -1, "", 11L);
        check("a non-RESULT word is ignored", "pinned=5555", prefixed.summary());

        TermuxResult values = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=port-closed port=5555 detail=a=b\n", "", 1, -1, "", 12L);
        check("values may contain '='", "a=b", values.values.get("detail"));
        check("every key is kept", "port-closed,5555,a=b",
                values.values.get("error") + "," + values.values.get("port") + ","
                        + values.values.get("detail"));

        checkTrue("a script path with a directory is recognised",
                TermuxResult.parse("/data/data/com.termux/files/home/wireless-adb-keeper/pin-adb-port.sh",
                        "RESULT pinned=5555\n", "", 0, -1, "", 13L).isPinScript());
        checkTrue("wifi-on.sh is recognised", crlf.isWifiScript());

        report("TermuxResultTest");
    }
}

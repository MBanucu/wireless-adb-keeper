package io.github.mbanucu.wirelessadbkeeper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Host-side probe for {@link TermuxResult}: the last {@code RESULT} line wins,
 * success needs exit code 0 and Termux's own {@code err == -1}, and a missing
 * line is explained rather than treated as success.
 *
 * <p>The Android half - receiving the bundle from Termux, the service and the
 * Status page - needs a phone. The scripts' half is checked by
 * {@code tools/result-contract.sh} with a fake adb.
 */
public final class ResultProbe {
    public static void main(String[] args) {
        int failures = 0;

        TermuxResult pinned = TermuxResult.parse("pin-adb-port.sh",
                "attaching...\nRESULT pinned=5555\n", "", 0, -1, "", 1L);
        System.out.println("pinned    " + pinned.uiLine());
        failures += check("plain success parses", pinned.ok());
        failures += check("success keeps the script's own port",
                "pinned=5555".equals(pinned.summary()));
        failures += check("success exposes the value",
                "5555".equals(pinned.values.get("pinned")));
        failures += check("success UI line carries stdout and exit code",
                pinned.uiLine().equals("pinned=5555 (exit 0)"));
        failures += check("success journal names the script and exit",
                pinned.journal().contains("script=pin-adb-port.sh")
                        && pinned.journal().contains("exit=0")
                        && pinned.journal().contains("err=-1"));

        TermuxResult failed = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=no-adbd\n", "adbd: no devices\n", 1, -1, "", 2L);
        System.out.println("failed    " + failed.uiLine());
        failures += check("error line is a failure", !failed.ok());
        failures += check("error keeps the script's reason",
                "error=no-adbd".equals(failed.summary()));
        failures += check("failure UI line carries the exit code and stderr",
                failed.uiLine().equals("error=no-adbd (exit 1); adbd: no devices"));

        TermuxResult last = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=no-adbd\nmore work\nRESULT pinned=5555\n", "", 0, -1, "", 3L);
        failures += check("the last RESULT line wins", last.ok()
                && "pinned=5555".equals(last.summary()));

        TermuxResult skipped = TermuxResult.parse("pin-adb-port.sh",
                "RESULT skipped=running\n", "", 0, -1, "", 13L);
        failures += check("a skipped pin is not an error", skipped.ok());
        failures += check("a skipped pin does not claim the port is listening",
                !skipped.reportsPinned());
        failures += check("a real pin reports the port", pinned.reportsPinned()
                && !failed.reportsPinned());

        TermuxResult crlf = TermuxResult.parse("wifi-on.sh",
                "wifi_on=1\r\nRESULT wifi=on\r\n", "", 0, -1, "", 4L);
        failures += check("CRLF output parses", crlf.ok()
                && "wifi=on".equals(crlf.summary()));

        TermuxResult nonzero = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", 3, -1, "", 5L);
        failures += check("a non-zero exit code is a failure", !nonzero.ok());

        TermuxResult internal = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", 0, 1, "Termux service was killed", 6L);
        failures += check("Termux's own error code is a failure", !internal.ok());

        TermuxResult noExit = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\n", "", null, -1, "", 7L);
        failures += check("a missing exit code is not guessed as success", !noExit.ok());

        TermuxResult silent = TermuxResult.parse("pin-adb-port.sh",
                "attaching...\n", "", 1, -1, "", 8L);
        System.out.println("silent    " + silent.uiLine());
        failures += check("no RESULT line is explained, not accepted", !silent.ok());
        failures += check("no RESULT line is named as such",
                silent.summary().equals("no RESULT line"));
        failures += check("no RESULT line still shows the exit code",
                silent.uiLine().equals("no RESULT line (exit 1)"));

        TermuxResult bare = TermuxResult.parse("wait_adb.sh", "RESULT\n", "", 0, -1, "", 9L);
        failures += check("a bare RESULT still parses", bare.ok()
                && "RESULT".equals(bare.summary()));

        TermuxResult prefixed = TermuxResult.parse("pin-adb-port.sh",
                "RESULT pinned=5555\nRESULTX pinned=6666\n", "", 0, -1, "", 10L);
        failures += check("a non-RESULT word is ignored", prefixed.ok()
                && "pinned=5555".equals(prefixed.summary()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("error", "port-closed");
        expected.put("port", "5555");
        expected.put("detail", "a=b");
        TermuxResult values = TermuxResult.parse("pin-adb-port.sh",
                "RESULT error=port-closed port=5555 detail=a=b\n", "", 1, -1, "", 11L);
        failures += check("several key=value tokens parse, even with '=' in a value",
                values.values.equals(expected));

        failures += check("a script path with a directory is still recognised",
                TermuxResult.parse("/data/data/com.termux/files/home/wireless-adb-keeper/pin-adb-port.sh",
                        "RESULT pinned=5555\n", "", 0, -1, "", 12L).isPinScript());
        failures += check("wifi-on.sh is recognised",
                crlf.isWifiScript());

        System.out.println(failures == 0 ? "OK" : failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The text of a control reply without Android: the status field lines, the
 * profile line, and the priority parser. The passphrase rule is checked here
 * rather than on a device: a profile line carries only set/none.
 */
public final class ControlTextTest {

    private ControlTextTest() {
    }

    public static void main(String[] args) {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("permission", "granted");
        status.put("profiles", "3");
        status.put("profile_active", "p2");
        check("status fields stay in order, one per line",
                "permission=granted\nprofiles=3\nprofile_active=p2",
                ControlText.lines(status));
        check("an empty status renders nothing", "", ControlText.lines(new LinkedHashMap<>()));
        check("a value from another field cannot break the reply",
                "last_result=one two three",
                ControlText.lines(fields("last_result", "one\ntwo\rthree")));
        check("a null value is an empty field", "last_result=",
                ControlText.lines(fields("last_result", null)));

        Profile first = new Profile("default", "WP22", "wpa2");
        check("a profile line has id, ssid, security, passphrase state and priority",
                "id=default ssid=WP22 security=wpa2 passphrase=set priority=1",
                ControlText.profile(first, 1, true));
        Profile second = new Profile("p1", "Lab", "");
        check("an empty security is rendered as auto",
                "id=p1 ssid=Lab security=auto passphrase=none priority=2",
                ControlText.profile(second, 2, false));
        String line = ControlText.profile(new Profile("p2", "Home\nWifi", "wpa3"), 3, true);
        checkTrue("an SSID with a newline stays on one line", !line.contains("\n"));
        checkTrue("the passphrase value never appears in a profile line",
                !line.contains("s3cret") && line.contains("passphrase=set"));

        check("a priority is parsed", Integer.valueOf(1), ControlText.priority("1"));
        check("surrounding space is accepted", Integer.valueOf(3),
                ControlText.priority(" 3 "));
        check("a non-number is not a priority", null, ControlText.priority("first"));
        check("an absent priority is null", null, ControlText.priority(null));
        check("an empty priority is not a number", null, ControlText.priority(""));
        check("security is passed through", "wpa3", ControlText.security("wpa3"));
        check("an empty security means auto", "auto", ControlText.security(""));
        check("a missing security means auto", "auto", ControlText.security(null));

        check("a paired reply is a key=value success",
                "paired=true\nresult=Paired. The app's key is now trusted by this phone.",
                ControlText.pairingReply(
                        "Paired. The app's key is now trusted by this phone.", false));
        check("a connected reply is a key=value success",
                "connected=true\nresult=Connected as uid=2000(shell)",
                ControlText.pairingReply("Connected as uid=2000(shell)", true));
        check("an unpaired reply is an error line, not a false code 0",
                "error=Pairing failed: wrong code",
                ControlText.pairingReply("Pairing failed: wrong code", false));
        check("a verify failure is an error line",
                "error=No connection yet: the phone does not trust this app's key",
                ControlText.pairingReply(
                        "No connection yet: the phone does not trust this app's key", true));
        check("an empty pairing reply is an error", "error=pairing returned no reply",
                ControlText.pairingReply("", false));
        check("a null pairing reply is an error", "error=pairing returned no reply",
                ControlText.pairingReply(null, false));
        check("a multi-line pairing reply stays one line",
                "error=Pairing failed: one two",
                ControlText.pairingReply("Pairing failed: one\ntwo", false));
        checkTrue("a paired reply is recognised as success",
                ControlText.pairingWorked("Paired. The app's key is now trusted by this phone."));
        checkTrue("a connected reply is recognised as success",
                ControlText.pairingWorked("Connected as uid=2000(shell)"));
        checkTrue("a failure is not success",
                !ControlText.pairingWorked("No connection yet: timeout"));
        checkTrue("a null reply is not success", !ControlText.pairingWorked(null));

        report("ControlTextTest");
    }

    private static Map<String, String> fields(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}

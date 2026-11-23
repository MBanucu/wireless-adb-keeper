package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeper's coded failures and the classification of a finished pin script.
 *
 * <p>Two rules are checked here rather than on a device: one failure produces
 * exactly one event, and the more specific reason wins (a missing
 * {@code allow-external-apps} hides the exit code it causes).
 */
public final class DiagReasonsTest {

    private DiagReasonsTest() {
    }

    public static void main(String[] args) {
        List<String> codes = new ArrayList<>();
        for (DiagReasons.Failure failure : DiagReasons.Failure.values()) {
            codes.add(failure.code());
            checkTrue("a Keeper failure has a stable area.code: " + failure.code(),
                    failure.code().matches("([a-z]+\\.)+[a-z_]+"));
            checkTrue("a Keeper failure has a known level: " + failure.code(),
                    level(failure.level()));
        }
        for (DiagReasons.PinReason reason : DiagReasons.PinReason.values()) {
            codes.add(reason.code());
            checkTrue("a pin reason has a stable code: " + reason.code(),
                    reason.code().matches("pin\\.[a-z_]+"));
            checkTrue("a pin reason has a known level: " + reason.code(),
                    level(reason.level()));
        }
        for (DiagReasons.ProfileChange change : DiagReasons.ProfileChange.values()) {
            codes.add(change.code());
            checkTrue("a profile change has a stable area.code: " + change.code(),
                    change.code().matches("profile\\.[a-z_]+"));
            checkTrue("a profile change has a known level: " + change.code(),
                    level(change.level()));
        }
        check("every code is distinct", codes.size(), new HashSet<>(codes).size());

        List<String> events = new ArrayList<>();
        DiagReasons.Sink sink = (level, code, detail) ->
                events.add(level + " " + code + " " + detail);
        for (DiagReasons.Failure failure : DiagReasons.Failure.values()) {
            String detail = "reason for " + failure.code();
            String reply = DiagReasons.fail(sink, failure, detail);
            check("one failure returns the same sentence: " + failure.code(),
                    "error=" + detail, reply);
        }
        check("one event per failure, no duplicates", DiagReasons.Failure.values().length,
                events.size());
        check("the first event carries the code and level", "warn mode.invalid"
                + " reason for mode.invalid", events.get(indexOf(DiagReasons.Failure.MODE_INVALID)));
        check("a key failure is journalled at error", "error key.load_failed"
                + " reason for key.load_failed", events.get(indexOf(
                        DiagReasons.Failure.KEY_LOAD_FAILED)));

        check("a clean pin result is pin.result", DiagReasons.PinReason.OK,
                DiagReasons.pinReason(result("RESULT pinned=5555\n", "", 0, -1, "")));
        check("a missing allow-external-apps wins over the exit code",
                DiagReasons.PinReason.EXTERNAL_APPS_OFF,
                DiagReasons.pinReason(result("", "", null, 1,
                        "allow-external-apps is not set to true")));
        check("a Termux error is a refused RUN_COMMAND", DiagReasons.PinReason.RUN_REFUSED,
                DiagReasons.pinReason(result("", "", null, 1, "could not run")));
        check("no RESULT line is not success", DiagReasons.PinReason.NO_RESULT,
                DiagReasons.pinReason(result("nothing here\n", "", 0, -1, "")));
        check("a non-zero exit is a script failure", DiagReasons.PinReason.SCRIPT_FAILED,
                DiagReasons.pinReason(result("RESULT error=no-adb\n", "", 1, -1, "")));
        check("a null result is no result, not success", DiagReasons.PinReason.NO_RESULT,
                DiagReasons.pinReason(null));

        check("a full profile store is wifi.profile.full",
                DiagReasons.Failure.PROFILE_FULL,
                DiagReasons.profileFailure(ProfileTable.ERROR_FULL));
        check("a profile encryption failure is wifi.secret.unsealable",
                DiagReasons.Failure.PASSPHRASE_UNSEALABLE,
                DiagReasons.profileFailure(ProfileTable.ERROR_SECRET));
        check("an unknown profile id is profile.unknown",
                DiagReasons.Failure.PROFILE_UNKNOWN,
                DiagReasons.profileFailure(ProfileTable.ERROR_UNKNOWN));
        check("an out-of-range priority is profile.priority.invalid",
                DiagReasons.Failure.PROFILE_PRIORITY,
                DiagReasons.profileFailure(ProfileTable.ERROR_PRIORITY));
        check("any other profile refusal is wifi.profile.save_failed",
                DiagReasons.Failure.PROFILE_SAVE_FAILED,
                DiagReasons.profileFailure(ProfileTable.ERROR_DUPLICATE));
        checkTrue("the profile cap is named in the fix",
                DiagReasons.profileDetail(ProfileTable.ERROR_FULL)
                        .contains(String.valueOf(ProfileTable.CAPACITY)));
        checkTrue("an unknown profile names the listing command",
                DiagReasons.profileDetail(ProfileTable.ERROR_UNKNOWN)
                        .contains("./keeper.sh profiles"));
        checkTrue("a profile save failure never repeats the password",
                !DiagReasons.profileDetail(ProfileTable.ERROR_SECRET)
                        .toLowerCase().contains("passphrase="));

        List<String> changes = new ArrayList<>();
        DiagReasons.Sink changeSink = (level, code, detail) ->
                changes.add(level + " " + code + " " + detail);
        for (DiagReasons.ProfileChange change : DiagReasons.ProfileChange.values()) {
            DiagReasons.changed(changeSink, change, "id=default ssid=Home");
        }
        check("one event per profile change", DiagReasons.ProfileChange.values().length,
                changes.size());
        check("an add is journalled at info with its code and detail",
                "info profile.added id=default ssid=Home", changes.get(0));
        check("a remove is journalled at info with its code and detail",
                "info profile.removed id=default ssid=Home",
                changes.get(DiagReasons.ProfileChange.REMOVED.ordinal()));

        String line = DiagFormat.line("2026-11-23T17:00:00Z", 10, "warn", "key.pair_failed",
                "passphrase=canary-pass token=canary-token pairing_code=canary-pair");
        checkTrue("a journal line never contains a passphrase", !line.contains("canary-pass"));
        checkTrue("a journal line never contains a control token", !line.contains("canary-token"));
        checkTrue("a journal line never contains a pairing code", !line.contains("canary-pair"));

        report("DiagReasonsTest");
    }

    private static TermuxResult result(String stdout, String stderr, Integer exit,
                                       int errCode, String errmsg) {
        return TermuxResult.parse("pin-adb-port.sh", stdout, stderr, exit, errCode, errmsg, 1L);
    }

    private static int indexOf(DiagReasons.Failure failure) {
        return failure.ordinal();
    }

    private static boolean level(String level) {
        Set<String> known = new HashSet<>();
        known.add("info");
        known.add("warn");
        known.add("error");
        return known.contains(level);
    }
}

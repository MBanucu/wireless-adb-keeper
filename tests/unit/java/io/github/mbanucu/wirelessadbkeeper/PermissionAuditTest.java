package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.List;

/**
 * The permission audit is the "why can it not do this" answer that must exist
 * before any button is pressed. These checks use only the pure mapping: a
 * revoked {@code WRITE_SECURE_SETTINGS} is a boolean here, and the resulting
 * row already carries the copyable grant command.
 */
public final class PermissionAuditTest {

    private static final String PKG = "io.github.mbanucu.wirelessadbkeeper";

    private PermissionAuditTest() {
    }

    public static void main(String[] args) {
        List<String> revoked = DiagSnapshot.lines(PermissionAudit.fields(
                new PermissionAudit.Inputs(PKG, 35,
                        false, false, false, false, false)));

        check("the audit has one row per capability", 6, revoked.size());
        check("a revoked WRITE_SECURE_SETTINGS shows before an action",
                "not granted; fix: adb shell pm grant " + PKG
                        + " android.permission.WRITE_SECURE_SETTINGS",
                value(revoked, "perm.write_secure_settings"));
        check("fine location shows its grant command",
                "not granted; fix: adb shell pm grant " + PKG
                        + " android.permission.ACCESS_FINE_LOCATION",
                value(revoked, "perm.fine_location"));
        check("a missing Termux is explained before RUN_COMMAND is tried",
                "not granted; fix: install Termux from F-Droid, then adb shell pm grant "
                        + PKG + " com.termux.permission.RUN_COMMAND",
                value(revoked, "perm.run_command"));
        check("notifications show the system-settings instruction",
                "not granted; fix: allow notifications in system settings",
                value(revoked, "perm.notifications"));
        check("the Termux row says how to install it",
                "not installed; fix: install Termux from F-Droid",
                value(revoked, "perm.termux"));
        check("allow-external-apps is reported once Termux is present",
                "not installed; fix: install Termux from F-Droid",
                value(revoked, "perm.termux_external_apps"));

        List<String> granted = DiagSnapshot.lines(PermissionAudit.fields(
                new PermissionAudit.Inputs(PKG, 35,
                        true, true, true, true, true)));
        check("a granted permission is just granted",
                "granted", value(granted, "perm.write_secure_settings"));
        check("a granted location is just granted",
                "granted", value(granted, "perm.fine_location"));
        check("a granted RUN_COMMAND is just granted",
                "granted", value(granted, "perm.run_command"));
        check("a granted notification is just granted",
                "granted", value(granted, "perm.notifications"));
        check("installed Termux is stated plainly",
                "installed", value(granted, "perm.termux"));
        check("allow-external-apps is honestly unverifiable",
                "not verifiable from this app; fix: set allow-external-apps=true"
                        + " in ~/.termux/termux.properties",
                value(granted, "perm.termux_external_apps"));

        List<String> beforeTen = DiagSnapshot.lines(PermissionAudit.fields(
                new PermissionAudit.Inputs(PKG, 28, true, false, true, false, true)));
        check("location is not demanded before Android 10",
                "not needed before Android 10", value(beforeTen, "perm.fine_location"));
        List<String> beforeThirteen = DiagSnapshot.lines(PermissionAudit.fields(
                new PermissionAudit.Inputs(PKG, 32, true, true, true, false, true)));
        check("notification permission is not pretended before Android 13",
                "no runtime permission before Android 13; allow notifications in system settings",
                value(beforeThirteen, "perm.notifications"));

        List<DiagSnapshot.Field> mixed = new ArrayList<>();
        mixed.add(new DiagSnapshot.Field("perm.before_unlock", () -> {
            throw new SecurityException("credential storage is locked");
        }));
        mixed.addAll(PermissionAudit.fields(new PermissionAudit.Inputs(
                PKG, 35, false, false, false, false, false)));
        List<String> rendered = DiagSnapshot.lines(mixed);
        check("one broken audit field still leaves every other row", 7, rendered.size());
        check("the broken row is unavailable, not missing",
                "perm.before_unlock=unavailable", rendered.get(0));
        checkTrue("the revoked write row survives a broken neighbour",
                rendered.contains("perm.write_secure_settings=not granted; fix: adb shell pm grant "
                        + PKG + " android.permission.WRITE_SECURE_SETTINGS"));
        checkTrue("no audit row contains a secret-shaped value",
                !String.join("\n", rendered).toLowerCase().contains("passphrase=")
                        && !String.join("\n", rendered).toLowerCase().contains("token="));

        report("PermissionAuditTest");
    }

    private static String value(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        }
        return null;
    }
}

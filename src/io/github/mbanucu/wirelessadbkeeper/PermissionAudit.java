package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.List;

/**
 * The permission audit shown on the Diagnostics page, without Android.
 *
 * <p>The page has to answer "why can it not do this" *before* a button is
 * pressed, so every capability the app relies on is a {@code perm.*} row with
 * its state and the exact command that fixes it. The rows are built from plain
 * booleans, which is what makes the audit testable on a JVM and what lets the
 * failing action repeat the same sentence the page already showed.
 *
 * <p>{@code allow-external-apps} is deliberately reported as unverifiable: it
 * is a property inside Termux's own private storage, and Android gives this
 * app no way to read it. Showing the requirement and the command is the
 * honest answer; guessing would turn a real failure into a wrong diagnosis.
 */
public final class PermissionAudit {

    public static final String WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";
    public static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    public static final String RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    /** The copyable adb line for one runtime permission. */
    public static String grantCommand(String packageName, String permission) {
        return "adb shell pm grant " + packageName + " " + permission;
    }

    /** Everything the audit needs, gathered once from the Context. */
    public static final class Inputs {
        private final String packageName;
        private final int sdk;
        private final boolean writeSecureSettings;
        private final boolean fineLocation;
        private final boolean runCommand;
        private final boolean notifications;
        private final boolean termuxInstalled;

        public Inputs(String packageName, int sdk, boolean writeSecureSettings,
                      boolean fineLocation, boolean runCommand, boolean notifications,
                      boolean termuxInstalled) {
            this.packageName = packageName;
            this.sdk = sdk;
            this.writeSecureSettings = writeSecureSettings;
            this.fineLocation = fineLocation;
            this.runCommand = runCommand;
            this.notifications = notifications;
            this.termuxInstalled = termuxInstalled;
        }

        public String packageName() {
            return packageName;
        }

        public int sdk() {
            return sdk;
        }

        public boolean writeSecureSettings() {
            return writeSecureSettings;
        }

        public boolean fineLocation() {
            return fineLocation;
        }

        public boolean runCommand() {
            return runCommand;
        }

        public boolean notifications() {
            return notifications;
        }

        public boolean termuxInstalled() {
            return termuxInstalled;
        }
    }

    private PermissionAudit() {
    }

    /** One {@code perm.*} row per capability, in a stable order. */
    public static List<DiagSnapshot.Field> fields(Inputs in) {
        List<DiagSnapshot.Field> rows = new ArrayList<>();
        String pkg = in.packageName();

        rows.add(row("perm.write_secure_settings", state(in.writeSecureSettings(),
                grantCommand(pkg, WRITE_SECURE_SETTINGS))));

        rows.add(row("perm.fine_location", in.sdk() < 29
                ? "not needed before Android 10"
                : state(in.fineLocation(), grantCommand(pkg, FINE_LOCATION))));

        rows.add(row("perm.run_command", !in.termuxInstalled()
                ? "not granted; fix: install Termux from F-Droid, then "
                        + grantCommand(pkg, RUN_COMMAND)
                : state(in.runCommand(), grantCommand(pkg, RUN_COMMAND))));

        rows.add(row("perm.notifications", in.sdk() < 33
                ? "no runtime permission before Android 13; allow notifications in system settings"
                : state(in.notifications(), "allow notifications in system settings")));

        rows.add(row("perm.termux", in.termuxInstalled()
                ? "installed"
                : "not installed; fix: install Termux from F-Droid"));

        rows.add(row("perm.termux_external_apps", in.termuxInstalled()
                ? "not verifiable from this app; fix: set allow-external-apps=true"
                        + " in ~/.termux/termux.properties"
                : "not installed; fix: install Termux from F-Droid"));

        return rows;
    }

    private static DiagSnapshot.Field row(String name, String value) {
        return new DiagSnapshot.Field(name, () -> value);
    }

    private static String state(boolean granted, String fix) {
        return granted ? "granted" : "not granted; fix: " + fix;
    }
}

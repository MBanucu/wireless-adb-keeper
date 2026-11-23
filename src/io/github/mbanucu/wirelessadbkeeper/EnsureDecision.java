package io.github.mbanucu.wirelessadbkeeper;

/**
 * Which journal entry {@link Ensurer#ensure} will produce, decided from inputs.
 *
 * <p>The order is the order the app already behaves in, and it matters:
 *
 * <table>
 *   <tr><th>#</th><th>Input</th><th>Reason</th><th>Code</th></tr>
 *   <tr><td>1</td><td>no WRITE_SECURE_SETTINGS</td>
 *       <td>{@link Reason#NO_PERMISSION}</td><td>ensure.no_permission</td></tr>
 *   <tr><td>2</td><td>Wi-Fi not connected</td>
 *       <td>{@link Reason#NO_WIFI}</td><td>ensure.no_wifi</td></tr>
 *   <tr><td>3</td><td>the settings read/write threw</td>
 *       <td>{@link Reason#FAILED}</td><td>ensure.failed</td></tr>
 *   <tr><td>4</td><td>wireless debugging is off</td>
 *       <td>{@link Reason#CHANGED}</td><td>ensure.changed</td></tr>
 *   <tr><td>5</td><td>wireless debugging is already on</td>
 *       <td>{@link Reason#NOOP}</td><td>ensure.noop</td></tr>
 * </table>
 *
 * <p>Permission is checked before Wi-Fi on purpose: missing permission is the
 * actionable failure even when the network is also down. Developer settings
 * being off is repaired on the way but does not change the reason: the code
 * already means "wireless debugging was enabled by this call". The pin, mode,
 * port and unlock signals that surround ensure are not inputs here: ensure
 * runs before the first unlock and must stay cheap, and those decisions live
 * in {@link PinnedPort}, {@link LocalWifi} and {@link MonitorService}.
 */
public final class EnsureDecision {

    /** The five journal outcomes, with the words each one records. */
    public enum Reason {
        NO_PERMISSION("warn", "ensure.no_permission", "Missing WRITE_SECURE_SETTINGS permission"),
        NO_WIFI("info", "ensure.no_wifi", "Wi-Fi not connected"),
        CHANGED("info", "ensure.changed", "Enabled wireless debugging"),
        NOOP("info", "ensure.noop", "Already enabled"),
        FAILED("error", "ensure.failed", "Error");

        private final String level;
        private final String code;
        private final String result;

        Reason(String level, String code, String result) {
            this.level = level;
            this.code = code;
            this.result = result;
        }

        public String level() {
            return level;
        }

        public String code() {
            return code;
        }

        public String result() {
            return result;
        }
    }

    /** Everything the ensure path knows at the point it decides. */
    public static final class Inputs {
        private final boolean hasPermission;
        private final boolean wifiConnected;
        private final boolean settingsReadable;
        private final boolean developerSettingsOn;
        private final boolean wirelessDebuggingOn;

        public Inputs(boolean hasPermission, boolean wifiConnected, boolean settingsReadable,
                      boolean developerSettingsOn, boolean wirelessDebuggingOn) {
            this.hasPermission = hasPermission;
            this.wifiConnected = wifiConnected;
            this.settingsReadable = settingsReadable;
            this.developerSettingsOn = developerSettingsOn;
            this.wirelessDebuggingOn = wirelessDebuggingOn;
        }

        public boolean hasPermission() {
            return hasPermission;
        }

        public boolean wifiConnected() {
            return wifiConnected;
        }

        public boolean settingsReadable() {
            return settingsReadable;
        }

        public boolean developerSettingsOn() {
            return developerSettingsOn;
        }

        public boolean wirelessDebuggingOn() {
            return wirelessDebuggingOn;
        }
    }

    private EnsureDecision() {
    }

    /** One reason, by the precedence table above. */
    public static Reason decide(Inputs in) {
        if (!in.hasPermission()) return Reason.NO_PERMISSION;
        if (!in.wifiConnected()) return Reason.NO_WIFI;
        if (!in.settingsReadable()) return Reason.FAILED;
        if (!in.wirelessDebuggingOn()) return Reason.CHANGED;
        return Reason.NOOP;
    }
}

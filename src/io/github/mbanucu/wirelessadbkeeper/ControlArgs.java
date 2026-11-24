package io.github.mbanucu.wirelessadbkeeper;

/**
 * A control call's method, positional argument and extras, resolved without a
 * Bundle.
 *
 * <p>The positional value means the SSID for {@code save_wifi}, the mode for
 * {@code set_mode} and the profile id for {@code remove_profile} and
 * {@code set_priority}; the password, the explicit save id and the priority only
 * travel as named extras. Reading the mode only from extras made
 * {@code keeper.sh mode local} pass null, and {@link Keeper#setMode} treats null
 * as "toggle" - so asking for the mode it was already in switched to the other
 * one. The fallback below is the rule that fixes that, and it is tested as the
 * bug rather than as a getter.
 */
public final class ControlArgs {

    /** A named-extra lookup; the provider adapts a Bundle, tests a map. */
    @FunctionalInterface
    public interface Extras {
        String get(String name);
    }

    /** The commands the dispatch understands, plus the unknown case. */
    public enum Method {
        STATUS,
        ENABLE,
        WIFI_ON,
        SERVICE_START,
        SERVICE_STOP,
        CONNECT,
        SET_MODE,
        SAVE_WIFI,
        PROFILES,
        REMOVE_PROFILE,
        SET_PRIORITY,
        PIN_PORT,
        PUBKEY,
        DIAG,
        SCHEDULE,
        AUTOSTART,
        TOKEN,
        UNKNOWN;

        public static Method of(String method) {
            if (method == null) return UNKNOWN;
            switch (method) {
                case "status": return STATUS;
                case "enable": return ENABLE;
                case "wifi_on": return WIFI_ON;
                case "service_start": return SERVICE_START;
                case "service_stop": return SERVICE_STOP;
                case "connect": return CONNECT;
                case "set_mode": return SET_MODE;
                case "save_wifi": return SAVE_WIFI;
                case "profiles": return PROFILES;
                case "remove_profile": return REMOVE_PROFILE;
                case "set_priority": return SET_PRIORITY;
                case "pin_port": return PIN_PORT;
                case "pubkey": return PUBKEY;
                case "diag": return DIAG;
                case "schedule": return SCHEDULE;
                case "autostart": return AUTOSTART;
                case "token": return TOKEN;
                default: return UNKNOWN;
            }
        }
    }

    /** The wire name, so an unknown method is echoed exactly as received. */
    public final String name;
    public final Method method;
    public final String mode;
    public final String ssid;
    public final String passphrase;
    /** An explicit profile for {@code save_wifi}, or the target of a profile call. */
    public final String id;
    /** The raw {@code priority} extra; {@link ControlText#priority} parses it. */
    public final String priority;
    /** The row to switch in the vendor list for {@code autostart}; null means this app. */
    public final String label;

    private ControlArgs(String name, Method method, String mode, String ssid, String passphrase,
                        String id, String priority, String label) {
        this.name = name;
        this.method = method;
        this.mode = mode;
        this.ssid = ssid;
        this.passphrase = passphrase;
        this.id = id;
        this.priority = priority;
        this.label = label;
    }

    /**
     * Resolves the call. An extras value wins; the positional argument is the
     * documented fallback for {@code set_mode}, {@code save_wifi},
     * {@code remove_profile} and {@code set_priority} only.
     */
    public static ControlArgs of(String method, String arg, Extras extras) {
        Method resolved = Method.of(method);
        String extraMode = extras == null ? null : extras.get("mode");
        String extraSsid = extras == null ? null : extras.get("ssid");
        String extraId = extras == null ? null : extras.get("id");
        String mode = extraMode != null || resolved != Method.SET_MODE ? extraMode : arg;
        String ssid = extraSsid != null || resolved != Method.SAVE_WIFI ? extraSsid : arg;
        boolean idIsPositional = resolved == Method.REMOVE_PROFILE
                || resolved == Method.SET_PRIORITY;
        String id = extraId != null || !idIsPositional ? extraId : arg;
        String passphrase = extras == null ? null : extras.get("passphrase");
        String priority = extras == null ? null : extras.get("priority");
        String extraLabel = extras == null ? null : extras.get("label");
        // autostart is the only command whose positional value is a label, and
        // an empty one means "this app", so null is the useful default.
        String label = extraLabel != null || resolved != Method.AUTOSTART ? extraLabel : arg;
        return new ControlArgs(method, resolved, mode, ssid, passphrase, id, priority, label);
    }

    public boolean known() {
        return method != Method.UNKNOWN;
    }
}

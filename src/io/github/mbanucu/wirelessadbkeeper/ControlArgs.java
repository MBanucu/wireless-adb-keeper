package io.github.mbanucu.wirelessadbkeeper;

/**
 * A control call's method, positional argument and extras, resolved without a
 * Bundle.
 *
 * <p>The positional value means the SSID for {@code save_wifi} and the mode for
 * {@code set_mode}; the password only travels as a named extra. Reading the
 * mode only from extras made {@code keeper.sh mode local} pass null, and
 * {@link Keeper#setMode} treats null as "toggle" - so asking for the mode it
 * was already in switched to the other one. The fallback below is the rule
 * that fixes that, and it is tested as the bug rather than as a getter.
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
        PIN_PORT,
        PUBKEY,
        DIAG,
        SCHEDULE,
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
                case "pin_port": return PIN_PORT;
                case "pubkey": return PUBKEY;
                case "diag": return DIAG;
                case "schedule": return SCHEDULE;
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

    private ControlArgs(String name, Method method, String mode, String ssid, String passphrase) {
        this.name = name;
        this.method = method;
        this.mode = mode;
        this.ssid = ssid;
        this.passphrase = passphrase;
    }

    /**
     * Resolves the call. An extras value wins; the positional argument is the
     * documented fallback for {@code set_mode} and {@code save_wifi} only.
     */
    public static ControlArgs of(String method, String arg, Extras extras) {
        Method resolved = Method.of(method);
        String extraMode = extras == null ? null : extras.get("mode");
        String extraSsid = extras == null ? null : extras.get("ssid");
        String mode = extraMode != null || resolved != Method.SET_MODE ? extraMode : arg;
        String ssid = extraSsid != null || resolved != Method.SAVE_WIFI ? extraSsid : arg;
        String passphrase = extras == null ? null : extras.get("passphrase");
        return new ControlArgs(method, resolved, mode, ssid, passphrase);
    }

    public boolean known() {
        return method != Method.UNKNOWN;
    }
}

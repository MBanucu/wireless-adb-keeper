package io.github.mbanucu.wirelessadbkeeper;

import java.util.Locale;

/**
 * The coded reasons a Keeper action or a pin attempt can fail, without Android.
 *
 * <p>Every path in {@link Keeper} that returns {@code error=...} goes through
 * {@link #fail}, which journals exactly one event and returns the same sentence
 * as the reply. The sentence already carries the fix, so a toast and the
 * diagnostics page cannot disagree about what to do next.
 *
 * <p>{@link PinReason} classifies what Termux sent back for
 * {@code pin-adb-port.sh}. Order matters: a missing {@code allow-external-apps}
 * is what Termux reports first, before the script even runs.
 */
public final class DiagReasons {

    /** Where one journal event goes; {@link Diag} is the app's real sink. */
    @FunctionalInterface
    public interface Sink {
        void event(String level, String code, String detail);
    }

    /** Keeper's action failures, each with a stable code and level. */
    public enum Failure {
        WIFI_ON_NO_TERMUX("warn", "wifi.on.no_termux"),
        SERVICE_START_REFUSED("warn", "svc.start_refused"),
        SERVICE_START_FAILED("error", "svc.start_failed"),
        LOCAL_UNSUPPORTED("warn", "local.unsupported"),
        LOCAL_NO_PERMISSION("warn", "local.no_permission"),
        LOCAL_SERVICE_REFUSED("warn", "local.service_refused"),
        MODE_INVALID("warn", "mode.invalid"),
        SSID_MISSING("warn", "wifi.ssid.missing"),
        PROFILE_FULL("warn", "wifi.profile.full"),
        PROFILE_SAVE_FAILED("warn", "wifi.profile.save_failed"),
        PASSPHRASE_UNSEALABLE("error", "wifi.secret.unsealable"),
        KEY_LOAD_FAILED("error", "key.load_failed");

        private final String level;
        private final String code;

        Failure(String level, String code) {
            this.level = level;
            this.code = code;
        }

        public String level() {
            return level;
        }

        public String code() {
            return code;
        }
    }

    /** What a finished pin script means, in the order the reasons are read. */
    public enum PinReason {
        OK("info", "pin.result"),
        EXTERNAL_APPS_OFF("warn", "pin.external_apps"),
        RUN_REFUSED("warn", "pin.run_refused"),
        NO_RESULT("warn", "pin.no_result"),
        SCRIPT_FAILED("warn", "pin.script_failed");

        private final String level;
        private final String code;

        PinReason(String level, String code) {
            this.level = level;
            this.code = code;
        }

        public String level() {
            return level;
        }

        public String code() {
            return code;
        }
    }

    private DiagReasons() {
    }

    /** Journals one event and returns the same reason as the error reply. */
    public static String fail(Sink sink, Failure failure, String detail) {
        sink.event(failure.level(), failure.code(), detail);
        return "error=" + detail;
    }

    /** Maps a profile-store refusal to the same codes the snapshot uses. */
    public static Failure profileFailure(String error) {
        if (ProfileTable.ERROR_SECRET.equals(error)) return Failure.PASSPHRASE_UNSEALABLE;
        if (ProfileTable.ERROR_FULL.equals(error)) return Failure.PROFILE_FULL;
        return Failure.PROFILE_SAVE_FAILED;
    }

    /** One sentence for a profile-store refusal, with the fix where there is one. */
    public static String profileDetail(String error) {
        if (ProfileTable.ERROR_SECRET.equals(error)) {
            return "could not encrypt the passphrase; the Keystore key may be gone;"
                    + " save the password again";
        }
        if (ProfileTable.ERROR_FULL.equals(error)) {
            return "profile limit is " + ProfileTable.CAPACITY
                    + "; remove a saved network first";
        }
        return "could not save the network: " + error;
    }

    /**
     * Classifies a pin result. A missing {@code allow-external-apps} is first
     * because Termux refuses the whole RUN_COMMAND with that message and the
     * script never runs; reporting the script's exit code then would be a
     * wrong diagnosis.
     */
    public static PinReason pinReason(TermuxResult result) {
        if (result == null) return PinReason.NO_RESULT;
        if (result.ok()) return PinReason.OK;
        String text = (nullToEmpty(result.errmsg) + " " + nullToEmpty(result.stderr))
                .toLowerCase(Locale.US);
        if (text.contains("allow-external-apps")) return PinReason.EXTERNAL_APPS_OFF;
        if (result.errCode != TermuxResult.ERR_OK) return PinReason.RUN_REFUSED;
        if (result.line == null) return PinReason.NO_RESULT;
        return PinReason.SCRIPT_FAILED;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import java.util.Map;

/**
 * The text of a control reply, decided without Android.
 *
 * <p>The provider wraps a reply as one {@code key=value} line per field, so the
 * rules that keep it parseable live here rather than in the provider: a value
 * never contains a newline, and a profile line never contains the passphrase.
 * That is why the profile renderer takes a boolean - the secret itself is only
 * ever read to answer "is it set?".
 */
public final class ControlText {

    private ControlText() {
    }

    /** One {@code key=value} line per map entry, in iteration order. */
    public static String lines(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append('=').append(oneLine(entry.getValue()));
        }
        return out.toString();
    }

    /** Keeps one field on one line so the reply stays parseable. */
    public static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * One profile line: id, SSID, security, whether a passphrase is stored and
     * the 1-based try order. The passphrase's value never appears here.
     */
    public static String profile(Profile profile, int priority, boolean passphraseSet) {
        return "id=" + oneLine(profile.id())
                + " ssid=" + oneLine(profile.ssid())
                + " security=" + security(profile.security())
                + " passphrase=" + (passphraseSet ? "set" : "none")
                + " priority=" + priority;
    }

    /** The reply word for a stored security; the store's empty value is auto. */
    public static String security(String value) {
        if (value == null || value.trim().isEmpty()) return "auto";
        return value.trim();
    }

    /**
     * Whether a pairing reply is a success, by the prefix the screen uses.
     *
     * <p>Pairing lives in {@code src-pairing}, which only the Gradle build
     * compiles, so the rule is kept here where both the screen and the control
     * surface can share it instead of each carrying its own string compare.
     */
    public static boolean pairingWorked(String reply) {
        return reply != null
                && (reply.startsWith("Paired") || reply.startsWith("Connected"));
    }

    /**
     * The control-surface form of a pairing reply.
     *
     * <p>The pairing screen gets a sentence to read; a script needs the same
     * {@code key=value} convention as every other action, and a failure needs
     * to be an {@code error=} line so the provider returns a non-zero code
     * instead of reporting a failed pairing as success.
     */
    public static String pairingReply(String reply, boolean verify) {
        if (reply == null || reply.trim().isEmpty()) {
            return "error=pairing returned no reply";
        }
        String line = oneLine(reply);
        if (!pairingWorked(reply)) return "error=" + line;
        return (verify ? "connected=true" : "paired=true") + "\nresult=" + line;
    }

    /** A priority from the wire, or null when it is not a decimal integer. */
    public static Integer priority(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

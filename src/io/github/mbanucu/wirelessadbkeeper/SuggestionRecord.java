package io.github.mbanucu.wirelessadbkeeper;

/**
 * The applied Wi-Fi suggestion record, encoded and decoded without Android.
 *
 * <p>The current shape is {@code ssid \0 security \0 passphrase}. Pre-upgrade
 * builds wrote {@code ssid \0 passphrase} instead, and always meant WPA2, so a
 * record with one separator is migrated to the three-field form on read. An
 * unknown or empty security field is also rewritten to WPA2: the builder would
 * silently take the WPA2 path for it, and the stored record has to match what
 * Android was actually handed.
 *
 * <p>Only the first two separators delimit fields. A passphrase may contain
 * {@code \0} (it is not a legal Wi-Fi password character, but old records are
 * not trusted to obey that) and must stay one record rather than being split.
 */
public final class SuggestionRecord {

    public static final String SEPARATOR = "\u0000";

    public static final String SEC_WPA2 = "wpa2";
    public static final String SEC_WPA3 = "wpa3";
    public static final String SEC_OWE = "owe";
    public static final String SEC_OPEN = "open";

    private final String ssid;
    private final String security;
    private final String passphrase;

    private SuggestionRecord(String ssid, String security, String passphrase) {
        this.ssid = ssid;
        this.security = security;
        this.passphrase = passphrase;
    }

    /** The record sealed in PassphraseStore: {@code ssid \0 security \0 pass}. */
    public static String encode(String ssid, String security, String pass) {
        return ssid + SEPARATOR + security + SEPARATOR + pass;
    }

    /**
     * Reads either record shape, or null when there is no usable record.
     *
     * <p>The two-field legacy form has no security field; it was written by a
     * build that only supported WPA2. The migration rule for an unknown
     * security value is the same WPA2 fallback, because that is the path the
     * suggestion builder took for it.
     */
    public static SuggestionRecord decode(String record) {
        if (record == null || record.isEmpty()) return null;
        int first = record.indexOf(SEPARATOR);
        if (first < 0) return null;
        int second = record.indexOf(SEPARATOR, first + 1);
        String ssid = record.substring(0, first);
        if (second < 0) {
            return new SuggestionRecord(ssid, SEC_WPA2, record.substring(first + 1));
        }
        String security = record.substring(first + 1, second);
        if (!isKnownSecurity(security)) {
            security = SEC_WPA2;
        }
        return new SuggestionRecord(ssid, security, record.substring(second + 1));
    }

    /** Rewrites a legacy plaintext record in the current shape, or "" if malformed. */
    public static String migrate(String legacy) {
        SuggestionRecord record = decode(legacy);
        return record == null ? "" : record.encode();
    }

    private static boolean isKnownSecurity(String security) {
        return SEC_WPA2.equals(security) || SEC_WPA3.equals(security)
                || SEC_OWE.equals(security) || SEC_OPEN.equals(security);
    }

    public String encode() {
        return encode(ssid, security, passphrase);
    }

    public String ssid() {
        return ssid;
    }

    public String security() {
        return security;
    }

    public String passphrase() {
        return passphrase;
    }
}

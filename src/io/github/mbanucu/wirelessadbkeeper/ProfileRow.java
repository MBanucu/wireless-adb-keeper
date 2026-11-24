package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One row of the Network page, decided without Android.
 *
 * <p>The page has to answer four things per saved network without ever reading
 * the passphrase into a view: what the network is, which security it uses,
 * whether a password is stored, and whether Android currently holds a
 * suggestion for it. It also has to mark the first profile as active and know
 * which way a row may move. Keeping that here means the navigation and the
 * empty-list state can be tested on a plain JVM, while {@link Profiles} supplies
 * the encrypted-slot answers and {@code MainActivity} only draws the views.
 *
 * <p>An "applied" suggestion is the last known state: the per-profile record is
 * written when Android accepts the suggestion and removed when the profile or
 * its suggestion is removed. A profile that has never been registered shows
 * "not applied" even though a running monitor may register it a moment later;
 * the page re-reads after every change for exactly that reason.
 */
public final class ProfileRow {

    public static final String EMPTY_TITLE = "No network saved";
    public static final String EMPTY_DETAIL =
            "Add a network below to let the keeper rejoin it after a restart.";

    /** Whether a profile has a password, without ever holding its value. */
    public enum Password {
        SAVED("password saved", "Saved - type a new password to replace"),
        NONE("no password", "Password (no password saved)"),
        UNREADABLE("password unreadable", "Saved password unreadable - type it again");

        private final String label;
        private final String hint;

        Password(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }
    }

    /** The last state of this profile's Android suggestion. */
    public enum Suggestion {
        APPLIED("suggestion applied"),
        NONE("suggestion not applied");

        private final String label;

        Suggestion(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The encrypted-slot answers; a test supplies a map, the app supplies Profiles. */
    public interface Lookup {
        Password password(String id);

        Suggestion suggestion(String id);
    }

    private final Profile profile;
    private final int position;
    private final int total;
    private final Password password;
    private final Suggestion suggestion;

    private ProfileRow(Profile profile, int position, int total, Password password,
                       Suggestion suggestion) {
        this.profile = profile;
        this.position = position;
        this.total = total;
        this.password = password == null ? Password.NONE : password;
        this.suggestion = suggestion == null ? Suggestion.NONE : suggestion;
    }

    /** All rows in try order; position 1 is the active profile. */
    public static List<ProfileRow> rows(List<Profile> profiles, Lookup lookup) {
        List<ProfileRow> rows = new ArrayList<>();
        if (profiles == null) return rows;
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            ProfileRow.Password password = lookup == null
                    ? ProfileRow.Password.NONE : lookup.password(profile.id());
            ProfileRow.Suggestion suggestion = lookup == null
                    ? ProfileRow.Suggestion.NONE : lookup.suggestion(profile.id());
            rows.add(new ProfileRow(profile, i + 1, profiles.size(), password, suggestion));
        }
        return rows;
    }

    public String id() {
        return profile.id();
    }

    public String ssid() {
        return profile.ssid();
    }

    /** auto|wpa2|wpa3|owe|open; empty is the store's "auto". */
    public String security() {
        return profile.security();
    }

    /** 1-based try order. */
    public int position() {
        return position;
    }

    /** The first profile is the one the keeper tries first. */
    public boolean active() {
        return position == 1;
    }

    public boolean canMoveUp() {
        return position > 1;
    }

    public boolean canMoveDown() {
        return position < total;
    }

    /** auto, WPA2, WPA3, OWE or open, as the badge on the row. */
    public String securityBadge() {
        return securityLabel(profile.security());
    }

    /** The display label for a stored security value; empty is auto. */
    public static String securityLabel(String security) {
        if (security == null || security.isEmpty()) return "auto";
        if (ProfileTable.SECURITY_WPA2.equals(security)) return "WPA2";
        if (ProfileTable.SECURITY_WPA3.equals(security)) return "WPA3";
        if (ProfileTable.SECURITY_OWE.equals(security)) return "OWE";
        if (ProfileTable.SECURITY_OPEN.equals(security)) return "open";
        return security.toUpperCase(Locale.US);
    }

    public Password passwordState() {
        return password;
    }

    public Suggestion suggestionState() {
        return suggestion;
    }

    public String passwordLabel() {
        return password.label();
    }

    public String suggestionLabel() {
        return suggestion.label();
    }

    /** The row's second line: password state and last suggestion state. */
    public String summary() {
        return password.label() + " · " + suggestion.label();
    }

    @Override
    public String toString() {
        return "ProfileRow{" + position + " " + profile.id() + " " + profile.ssid()
                + " (" + securityBadge() + ") " + summary() + "}";
    }
}

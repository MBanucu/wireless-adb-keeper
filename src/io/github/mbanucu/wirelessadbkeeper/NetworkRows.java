package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Network page shows, decided without Android types.
 *
 * <p>One {@link Row} per saved profile, in try order: the SSID, the security
 * badge, whether a password is stored and the last suggestion status. The first
 * row is the active profile, so it carries the marker that says which network
 * is tried first. The page only builds views from these rows and calls
 * {@link Profiles} after every change; this class owns the words so a plain JVM
 * suite can check them.
 */
public final class NetworkRows {

    /** Shown when the list is empty, including after deleting the last profile. */
    public static final String EMPTY_SUMMARY = "No network saved";
    public static final String MARKER = "ACTIVE - TRIED FIRST";

    /**
     * What the encrypted store says about a profile's password: the value is
     * readable, absent, or present but undecryptable. "Undecryptable" must not
     * be shown as "no password": after a reboot with an unusable Keystore that
     * is the difference between "type one in" and "your saved one is gone".
     */
    public enum PasswordState {
        SAVED,
        MISSING,
        UNREADABLE
    }

    private NetworkRows() {
    }

    /** Maps a decoded secret to the page's state; null means decryption failed. */
    public static PasswordState passwordState(String secret) {
        if (secret == null) return PasswordState.UNREADABLE;
        return secret.isEmpty() ? PasswordState.MISSING : PasswordState.SAVED;
    }

    /** One profile's row: immutable, with the page's labels already decided. */
    public static final class Row {
        private final String id;
        private final String ssid;
        private final String security;
        private final boolean active;
        private final PasswordState passwordState;
        private final String status;

        Row(String id, String ssid, String security, boolean active,
            PasswordState passwordState, String status) {
            this.id = id == null ? "" : id;
            this.ssid = ssid == null ? "" : ssid;
            this.security = security == null ? "" : security;
            this.active = active;
            this.passwordState = passwordState == null ? PasswordState.MISSING : passwordState;
            this.status = status == null ? "" : status;
        }

        public String id() {
            return id;
        }

        public String ssid() {
            return ssid;
        }

        public boolean active() {
            return active;
        }

        public PasswordState passwordState() {
            return passwordState;
        }

        /** auto, WPA2, WPA3, OWE or open. */
        public String badge() {
            return securityBadge(security);
        }

        /** password saved, no password, or password unreadable. */
        public String passwordLabel() {
            return NetworkRows.passwordLabel(passwordState);
        }

        /** The last per-profile suggestion status, or a defined "not tried yet". */
        public String statusLabel() {
            return NetworkRows.statusLabel(status);
        }

        /** The active marker, or "" for every other row. */
        public String marker() {
            return active ? MARKER : "";
        }

        /** The badges under the SSID: security, password state, suggestion state. */
        public String subtitle() {
            return badge() + " | " + passwordLabel() + " | " + statusLabel();
        }

        /** One line that names the row; useful for accessibility and the suite. */
        public String summary() {
            return ssid + " - " + subtitle();
        }
    }

    /**
     * Builds the rows in the order given. Only the first profile is active;
     * {@code withSecret} holds the ids whose passphrase is readable, and
     * {@code statuses} maps an id to the word from the last suggestion pass.
     */
    public static List<Row> rows(List<Profile> profiles, Set<String> withSecret,
                                 Map<String, String> statuses) {
        Map<String, PasswordState> states = new LinkedHashMap<>();
        if (withSecret != null) {
            for (String id : withSecret) {
                states.put(id, PasswordState.SAVED);
            }
        }
        return rows(profiles, states, statuses);
    }

    /**
     * Builds the rows from each profile's password state. A profile absent
     * from {@code passwordStates} has no saved password; {@link
     * PasswordState#UNREADABLE} is the case that must stay visible.
     */
    public static List<Row> rows(List<Profile> profiles,
                                 Map<String, PasswordState> passwordStates,
                                 Map<String, String> statuses) {
        List<Row> rows = new ArrayList<>();
        if (profiles == null) return Collections.unmodifiableList(rows);
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            if (profile == null) continue;
            String status = statuses == null ? null : statuses.get(profile.id());
            PasswordState state = passwordStates == null
                    ? null : passwordStates.get(profile.id());
            rows.add(new Row(profile.id(), profile.ssid(), profile.security(), i == 0,
                    state == null ? PasswordState.MISSING : state, status));
        }
        return Collections.unmodifiableList(rows);
    }

    /** The security word as the badge: auto, WPA2, WPA3, OWE or open. */
    public static String securityBadge(String security) {
        String value = security == null ? "" : security.trim().toLowerCase(Locale.US);
        if (ProfileTable.SECURITY_WPA2.equals(value)) return "WPA2";
        if (ProfileTable.SECURITY_WPA3.equals(value)) return "WPA3";
        if (ProfileTable.SECURITY_OWE.equals(value)) return "OWE";
        if (ProfileTable.SECURITY_OPEN.equals(value)) return "open";
        return "auto";
    }

    public static String passwordLabel(boolean saved) {
        return saved ? "password saved" : "no password";
    }

    /** The row label for the three states; unreadable is never shown as absent. */
    public static String passwordLabel(PasswordState state) {
        if (state == PasswordState.SAVED) return "password saved";
        if (state == PasswordState.UNREADABLE) return "password unreadable - save it again";
        return "no password";
    }

    /** Never prefill a password field; the hint only says whether one exists. */
    public static String passwordHint(boolean saved) {
        return passwordHint(saved ? PasswordState.SAVED : PasswordState.MISSING);
    }

    /** The field hint for the three states. */
    public static String passwordHint(PasswordState state) {
        if (state == PasswordState.SAVED) return "Saved - type a new password to replace";
        if (state == PasswordState.UNREADABLE) {
            return "Saved password cannot be decrypted - type it again";
        }
        return "Password";
    }

    /**
     * The diagnostics snapshot's one-line password audit. Counts only, never a
     * secret; an unreadable count carries the fix in the same line.
     */
    public static String passwordSummary(int saved, int missing, int unreadable) {
        StringBuilder out = new StringBuilder("saved=").append(saved)
                .append(" missing=").append(missing)
                .append(" unreadable=").append(unreadable);
        if (unreadable > 0) out.append("; save the password again");
        return out.toString();
    }

    /** "suggestion: active", "suggestion: enter Wi-Fi password", ... */
    public static String statusLabel(String status) {
        String value = status == null ? "" : status.trim();
        return value.isEmpty() ? "suggestion: not tried yet" : "suggestion: " + value;
    }

    /**
     * The per-profile statuses from {@code SuggestionRegistry.Outcome.detail()}
     * ("suggestion p1: active; suggestion p2: enter Wi-Fi password"). In
     * local-only mode the monitor's detail is the SSID, so this returns nothing.
     */
    public static Map<String, String> parseStatuses(String detail) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (detail == null) return statuses;
        for (String part : detail.split(";")) {
            String line = part.trim();
            if (!line.startsWith("suggestion ")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String id = line.substring("suggestion ".length(), colon).trim();
            String status = line.substring(colon + 1).trim();
            if (!id.isEmpty() && !status.isEmpty()) statuses.put(id, status);
        }
        return statuses;
    }

    /** The line above the list; says which network is tried first. */
    public static String summary(List<Row> rows) {
        if (rows == null || rows.isEmpty()) return EMPTY_SUMMARY;
        String first = rows.get(0).ssid();
        if (rows.size() == 1) return "1 network saved. " + first + " is tried first.";
        return rows.size() + " networks saved. " + first + " is tried first.";
    }

    /** Names the SSID and says the password goes with the profile. */
    public static String deleteConfirmation(String ssid) {
        String clean = ssid == null ? "" : ssid.trim();
        String name = clean.isEmpty() ? "this network" : "\"" + clean + "\"";
        return "Delete " + name + "? The saved password will be discarded."
                + " This cannot be undone.";
    }
}

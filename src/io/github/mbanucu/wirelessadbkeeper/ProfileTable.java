package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The saved-network list, decided without Android types.
 *
 * <p>Everything the store needs to get right is here: the ordered id list, the
 * one-profile-per-SSID rule, the cap, opaque id allocation, the legacy
 * migration and the slot names. {@link Profiles} is the thin Android adapter
 * that supplies prefs and {@link PassphraseStore}; this class can be tested on
 * a plain JVM.
 *
 * <p>The prefs shape is:
 *
 * <pre>
 * profile_ids              comma-separated ids, order is try order
 * profile.&lt;id&gt;.ssid       the network name
 * profile.&lt;id&gt;.security   auto|wpa2|wpa3|owe|open, empty = decide from the scan
 * </pre>
 *
 * <p>The passphrase never appears in those keys. A profile's secret lives in
 * {@link PassphraseStore} under the slot {@code profile.<id>}, and the record of
 * what was registered with Android lives under {@code suggestion_applied.<id>}.
 * An id is short and opaque ({@code default}, {@code p1}, {@code p2}, ...), and
 * never an SSID: an SSID can contain a comma, which {@code profile_ids} uses as
 * its separator.
 *
 * <p>Migration happens on the first read. An install from before profiles kept
 * one {@code ssid} key and one unnamed passphrase; both become the profile
 * {@code default} at the front of the list. The secret is moved in one commit
 * and the legacy SSID plus the new metadata are written in another, so a crash
 * between them migrates again rather than leaving a profile with no password.
 * A user who never opened the Network page is migrated by the monitor's own
 * first read, so the keeper keeps working.
 */
public final class ProfileTable {

    /** A runaway script cannot fill prefs or the suggestion list past this. */
    public static final int CAPACITY = 16;

    public static final String DEFAULT_ID = "default";
    public static final String IDS_KEY = "profile_ids";
    /** The pre-profiles SSID key; matches LocalWifi.KEY_SSID, read once by migrate. */
    public static final String LEGACY_SSID_KEY = "ssid";
    /** The single applied-suggestion slot older builds wrote to. */
    public static final String LEGACY_APPLIED_SLOT = "wifi_suggestion_applied";

    public static final String SECURITY_AUTO = "";
    public static final String SECURITY_WPA2 = "wpa2";
    public static final String SECURITY_WPA3 = "wpa3";
    public static final String SECURITY_OWE = "owe";
    public static final String SECURITY_OPEN = "open";

    public static final String ERROR_FULL = "profile limit is " + CAPACITY;
    public static final String ERROR_SSID = "ssid is required";
    public static final String ERROR_SECURITY =
            "security must be auto, wpa2, wpa3, owe or open";
    public static final String ERROR_UNKNOWN = "no such profile";
    public static final String ERROR_DUPLICATE = "another profile already uses this SSID";
    public static final String ERROR_SECRET = "could not encrypt the passphrase";
    public static final String ERROR_PRIORITY = "priority is out of range";

    /** The non-secret prefs, adapted from SharedPreferences or a test map. */
    public interface Store {
        String get(String key);

        /** Applies every put and remove in one commit. */
        void write(Map<String, String> puts, Set<String> removes);
    }

    /**
     * The encrypted slots, adapted from {@link PassphraseStore} or a test map.
     * An empty value clears the slot, as {@link PassphraseStore#putSecret} does.
     */
    public interface Secrets {
        boolean put(String slot, String value);

        void clear(String slot);

        /**
         * Moves the pre-slot passphrase to {@code slot} in one commit. True means
         * the slot is settled (absent legacy included); false means encryption
         * failed and the legacy keys were left in place.
         */
        boolean adoptLegacy(String slot);
    }

    /** A save or delete result: the profile, or a named error and no change. */
    public static final class Outcome {
        private final Profile profile;
        private final String error;

        private Outcome(Profile profile, String error) {
            this.profile = profile;
            this.error = error;
        }

        static Outcome ok(Profile profile) {
            return new Outcome(profile, null);
        }

        static Outcome fail(String error) {
            return new Outcome(null, error);
        }

        public boolean ok() {
            return error == null;
        }

        public Profile profile() {
            return profile;
        }

        /** Null on success. */
        public String error() {
            return error;
        }
    }

    private final Store store;
    private final Secrets secrets;

    public ProfileTable(Store store, Secrets secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    public static String ssidKey(String id) {
        return "profile." + id + ".ssid";
    }

    public static String securityKey(String id) {
        return "profile." + id + ".security";
    }

    /** The PassphraseStore slot for a profile's password, never a prefs key. */
    public static String secretSlot(String id) {
        return "profile." + id;
    }

    /** Where a profile's applied-suggestion record belongs. */
    public static String appliedSlot(String id) {
        return "suggestion_applied." + id;
    }

    /** Auto for null/empty/auto; the stored word for the four known values. */
    public static String normalizeSecurity(String value) {
        if (value == null) return SECURITY_AUTO;
        String clean = value.trim().toLowerCase(Locale.US);
        if (clean.isEmpty() || "auto".equals(clean)) return SECURITY_AUTO;
        if (SECURITY_WPA2.equals(clean) || SECURITY_WPA3.equals(clean)
                || SECURITY_OWE.equals(clean) || SECURITY_OPEN.equals(clean)) {
            return clean;
        }
        return null;
    }

    /** The ordered profiles, after the legacy migration on first read. */
    public synchronized List<Profile> list() {
        migrate();
        List<String> ids = ids();
        List<Profile> profiles = new ArrayList<>(ids.size());
        for (String id : ids) {
            String ssid = store.get(ssidKey(id));
            if (ssid == null || ssid.isEmpty()) continue;
            profiles.add(new Profile(id, ssid, storedSecurity(store.get(securityKey(id)))));
        }
        return profiles;
    }

    /** The first profile, which is the one the single-network API uses. */
    public synchronized Profile active() {
        List<Profile> profiles = list();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public synchronized Profile bySsid(String ssid) {
        String clean = clean(ssid);
        if (clean == null || clean.isEmpty()) return null;
        for (Profile profile : list()) {
            if (profile.ssid().equals(clean)) return profile;
        }
        return null;
    }

    public synchronized int count() {
        return list().size();
    }

    /**
     * Saves by SSID: an existing network is updated in place, a new one is
     * appended. Adding past {@link #CAPACITY} fails with {@link #ERROR_FULL}
     * and writes nothing.
     *
     * <p>A null security keeps an existing profile's value (and means auto for a
     * new one). A null secret keeps the stored one; an empty secret clears it.
     */
    public synchronized Outcome save(String ssid, String security, String secret) {
        migrate();
        String clean = clean(ssid);
        if (clean == null || clean.isEmpty()) return Outcome.fail(ERROR_SSID);

        String normalized = null;
        if (security != null) {
            normalized = normalizeSecurity(security);
            if (normalized == null) return Outcome.fail(ERROR_SECURITY);
        }

        List<String> ids = ids();
        for (String id : ids) {
            Profile existing = read(id);
            if (existing != null && existing.ssid().equals(clean)) {
                return updateInternal(ids, id, existing, clean,
                        normalized == null ? existing.security() : normalized, secret);
            }
        }

        if (ids.size() >= CAPACITY) return Outcome.fail(ERROR_FULL);
        String id = nextId(ids);
        String effective = normalized == null ? SECURITY_AUTO : normalized;
        if (!putSecret(id, secret)) return Outcome.fail(ERROR_SECRET);

        List<String> updated = new ArrayList<>(ids);
        updated.add(id);
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put(IDS_KEY, join(updated));
        puts.put(ssidKey(id), clean);
        puts.put(securityKey(id), effective);
        store.write(puts, new LinkedHashSet<String>());
        return Outcome.ok(new Profile(id, clean, effective));
    }

    /** The add spelling of {@link #save}; an existing SSID still updates. */
    public synchronized Outcome add(String ssid, String security, String secret) {
        return save(ssid, security, secret);
    }

    /** Edits one profile; the id survives a rename. */
    public synchronized Outcome update(String id, String ssid, String security, String secret) {
        migrate();
        if (id == null || id.isEmpty()) return Outcome.fail(ERROR_UNKNOWN);
        String clean = clean(ssid);
        if (clean == null || clean.isEmpty()) return Outcome.fail(ERROR_SSID);

        String normalized = null;
        if (security != null) {
            normalized = normalizeSecurity(security);
            if (normalized == null) return Outcome.fail(ERROR_SECURITY);
        }

        List<String> ids = ids();
        Profile existing = read(id);
        if (existing == null || !ids.contains(id)) return Outcome.fail(ERROR_UNKNOWN);
        for (String other : ids) {
            if (other.equals(id)) continue;
            Profile candidate = read(other);
            if (candidate != null && candidate.ssid().equals(clean)) {
                return Outcome.fail(ERROR_DUPLICATE);
            }
        }
        return updateInternal(ids, id, existing, clean,
                normalized == null ? existing.security() : normalized, secret);
    }

    /**
     * Deletes a profile, its ciphertext and its applied-suggestion record. The
     * first profile is the one the single-network API applied, so its legacy
     * applied slot is cleared too.
     */
    public synchronized Outcome remove(String id) {
        migrate();
        List<String> ids = ids();
        int index = ids.indexOf(id);
        if (index < 0) return Outcome.fail(ERROR_UNKNOWN);
        Profile removed = read(id);

        List<String> updated = new ArrayList<>(ids);
        updated.remove(index);
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put(IDS_KEY, join(updated));
        Set<String> removes = new LinkedHashSet<>();
        removes.add(ssidKey(id));
        removes.add(securityKey(id));
        store.write(puts, removes);

        secrets.clear(secretSlot(id));
        secrets.clear(appliedSlot(id));
        if (index == 0) secrets.clear(LEGACY_APPLIED_SLOT);
        return Outcome.ok(removed == null ? new Profile(id, "", SECURITY_AUTO) : removed);
    }

    /**
     * Moves a profile to a 1-based position in the try order.
     *
     * <p>The order is the {@code profile_ids} list itself, so a move is one
     * write of that marker and nothing else: the SSID, security and secret stay
     * with the id. A position outside {@code 1..count} is refused without
     * touching the store.
     */
    public synchronized Outcome move(String id, int priority) {
        migrate();
        List<String> ids = ids();
        int from = id == null ? -1 : ids.indexOf(id);
        if (from < 0) return Outcome.fail(ERROR_UNKNOWN);
        if (priority < 1 || priority > ids.size()) return Outcome.fail(ERROR_PRIORITY);

        List<String> updated = new ArrayList<>(ids);
        updated.remove(from);
        updated.add(priority - 1, id);
        if (!updated.equals(ids)) {
            Map<String, String> puts = new LinkedHashMap<>();
            puts.put(IDS_KEY, join(updated));
            store.write(puts, new LinkedHashSet<String>());
        }
        Profile moved = read(id);
        return Outcome.ok(moved == null ? new Profile(id, "", SECURITY_AUTO) : moved);
    }

    private Outcome updateInternal(List<String> ids, String id, Profile existing, String ssid,
                                   String security, String secret) {
        if (!putSecret(id, secret)) return Outcome.fail(ERROR_SECRET);
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put(IDS_KEY, join(ids));
        puts.put(ssidKey(id), ssid);
        puts.put(securityKey(id), security);
        store.write(puts, new LinkedHashSet<String>());
        return Outcome.ok(new Profile(id, ssid, security));
    }

    /** Null means keep; empty clears; a value is sealed by the adapter. */
    private boolean putSecret(String id, String secret) {
        if (secret == null) return true;
        return secrets.put(secretSlot(id), secret);
    }

    /**
     * One commit: the list marker and the SSID, with the pre-profile key gone.
     * The secret is moved by {@link Secrets#adoptLegacy} before this write, so
     * a crash cannot leave an active profile with no password.
     */
    private void migrate() {
        if (store.get(IDS_KEY) != null) return;
        String legacy = clean(store.get(LEGACY_SSID_KEY));
        if (legacy == null || legacy.isEmpty()) return;

        if (!secrets.adoptLegacy(secretSlot(DEFAULT_ID))) {
            // The Keystore could not seal the old passphrase. Leave the legacy
            // keys and write no marker, so the next read retries instead of
            // creating an active profile with no password.
            return;
        }
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put(IDS_KEY, DEFAULT_ID);
        puts.put(ssidKey(DEFAULT_ID), legacy);
        puts.put(securityKey(DEFAULT_ID), SECURITY_AUTO);
        Set<String> removes = new LinkedHashSet<>();
        removes.add(LEGACY_SSID_KEY);
        store.write(puts, removes);
    }

    private List<String> ids() {
        String raw = store.get(IDS_KEY);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String id = part.trim();
            if (id.isEmpty() || !seen.add(id)) continue;
            ids.add(id);
        }
        return ids;
    }

    private Profile read(String id) {
        String ssid = store.get(ssidKey(id));
        if (ssid == null || ssid.isEmpty()) return null;
        return new Profile(id, ssid, storedSecurity(store.get(securityKey(id))));
    }

    /** An id is opaque; reuse the smallest free pN so the list stays compact. */
    private static String nextId(List<String> ids) {
        for (int n = 1; n <= CAPACITY; n++) {
            String id = "p" + n;
            if (!ids.contains(id)) return id;
        }
        return null;
    }

    private static String storedSecurity(String value) {
        String normalized = normalizeSecurity(value);
        return normalized == null ? SECURITY_AUTO : normalized;
    }

    private static String join(List<String> ids) {
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }
}

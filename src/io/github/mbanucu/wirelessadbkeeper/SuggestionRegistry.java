package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The per-profile network-suggestion ledger, decided without Android types.
 *
 * <p>Android 10 can only remove a suggestion by handing back an object that
 * matches what was added, so every profile needs a record of what this app
 * actually registered. The record keeps the same encrypted shape as before
 * ({@code ssid \0 security \0 passphrase}) and lives in
 * {@link PassphraseStore} under {@code suggestion_applied.<id>}. A build before
 * profiles kept one slot; {@link #migrate} moves it to the first profile, which
 * is the network that single-slot build applied.
 *
 * <p>The rules are the ones {@link WifiSuggestion} used for its one network,
 * now per profile: an identical record is not rewritten (re-adding makes
 * Android drop the network for a moment), a changed record is removed and
 * added again, and a profile with no usable credential is skipped by id
 * instead of aborting the rest. The owning adapter supplies the suggestion
 * manager and the encrypted slots, so this class also runs on a plain JVM.
 */
public final class SuggestionRegistry {

    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_NO_SERVICE = -1;
    public static final int STATUS_NO_SECRET = -2;
    public static final int STATUS_BUILD_FAILED = -3;
    public static final int STATUS_REQUEST_FAILED = -4;

    /** Why one profile ended up with the status it has. */
    public enum Reason {
        CURRENT,
        ADDED,
        ADDED_UNSEALED,
        NO_PASSWORD,
        DECRYPT_FAILED,
        BUILD_FAILED,
        REQUEST_FAILED,
        REFUSED
    }

    /** The encrypted record slots, adapted from {@link PassphraseStore} or a test map. */
    public interface Secrets {
        /** An absent slot is "", null means it cannot be decrypted. */
        String get(String slot);

        boolean put(String slot, String value);

        void clear(String slot);
    }

    /** The platform side: one add or remove of one profile's suggestion. */
    public interface Manager {
        /** Adds one record and returns Android's status, or one of the negatives here. */
        int add(String id, SuggestionRecord record);

        /** Removes one record and returns how many suggestions it matched. */
        int remove(String id, SuggestionRecord record);

        /** This app's held suggestions; -1 when the version cannot report it. */
        int count();
    }

    /** One profile's result: the id names it even when the status is a skip. */
    public static final class Entry {
        private final String id;
        private final String ssid;
        private final String security;
        private final Reason reason;
        private final int status;
        private final boolean recordLost;

        Entry(String id, String ssid, String security, Reason reason, int status,
              boolean recordLost) {
            this.id = id;
            this.ssid = ssid;
            this.security = security;
            this.reason = reason;
            this.status = status;
            this.recordLost = recordLost;
        }

        public String id() {
            return id;
        }

        public String ssid() {
            return ssid;
        }

        public String security() {
            return security;
        }

        public Reason reason() {
            return reason;
        }

        public int status() {
            return status;
        }

        /** True when the applied record could not be read and was rebuilt. */
        public boolean recordLost() {
            return recordLost;
        }

        public boolean ok() {
            return status == STATUS_SUCCESS;
        }

        /** The word in `suggestion <id>: <status>`. */
        public String text() {
            switch (reason) {
                case CURRENT:
                case ADDED:
                case ADDED_UNSEALED:
                    return "active";
                case NO_PASSWORD:
                    return "enter Wi-Fi password";
                case DECRYPT_FAILED:
                    return "passphrase unreadable";
                case BUILD_FAILED:
                    return "could not build suggestion";
                case REQUEST_FAILED:
                    return "suggestion request failed";
                default:
                    return "status " + status;
            }
        }
    }

    /** The per-profile results of one registration pass. */
    public static final class Outcome {
        private final List<Entry> entries;
        private final int status;
        private final String detail;

        Outcome(List<Entry> entries, int status, String detail) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.status = status;
            this.detail = detail;
        }

        /** No Wi-Fi service, so no profile was attempted. */
        public static Outcome unavailable() {
            return new Outcome(new ArrayList<Entry>(), STATUS_NO_SERVICE,
                    "suggestion: no Wi-Fi service");
        }

        public boolean ok() {
            return status == STATUS_SUCCESS;
        }

        /** The first non-success status, or 0 when every profile registered. */
        public int status() {
            return status;
        }

        /** `suggestion <id>: <status>` per profile, joined with "; ". */
        public String detail() {
            return detail;
        }

        public List<Entry> entries() {
            return entries;
        }
    }

    private final Secrets secrets;
    private final Manager manager;

    public SuggestionRegistry(Secrets secrets, Manager manager) {
        this.secrets = secrets;
        this.manager = manager;
    }

    /**
     * Registers every profile and reports one status per profile.
     *
     * <p>Open and OWE profiles take no secret even if a stale one is stored. A
     * profile whose passphrase is missing or unreadable is skipped with
     * {@link #STATUS_NO_SECRET}; it does not abort the profiles after it. An
     * Android rejection (or a request failure) does abort the pass: repeating
     * it for every profile would only fill the journal and the notification.
     */
    public Outcome addAll(List<Profile> profiles) {
        migrate(profiles);
        List<Entry> entries = new ArrayList<>();
        int held = manager.count();
        if (held < 0) held = count(profiles);
        int overall = STATUS_SUCCESS;

        for (Profile profile : profiles) {
            String id = profile.id();
            String ssid = profile.ssid();
            String security = profile.security();
            if (security == null || security.isEmpty()) security = SuggestionRecord.SEC_WPA2;
            String slot = ProfileTable.appliedSlot(id);
            String applied = secrets.get(slot);
            boolean recordLost = applied == null;
            if (recordLost) {
                secrets.clear(slot);
                applied = "";
            }

            String secret = secrets.get(ProfileTable.secretSlot(id));
            if (SuggestionRecord.SEC_OPEN.equals(security)
                    || SuggestionRecord.SEC_OWE.equals(security)) {
                // Open and OWE APs need no secret, even if a stale one is stored.
                secret = "";
            } else if (secret == null) {
                entries.add(new Entry(id, ssid, security, Reason.DECRYPT_FAILED,
                        STATUS_NO_SECRET, recordLost));
                if (overall == STATUS_SUCCESS) overall = STATUS_NO_SECRET;
                continue;
            } else if (secret.isEmpty()) {
                entries.add(new Entry(id, ssid, security, Reason.NO_PASSWORD,
                        STATUS_NO_SECRET, recordLost));
                if (overall == STATUS_SUCCESS) overall = STATUS_NO_SECRET;
                continue;
            }

            String wanted = SuggestionRecord.encode(ssid, security, secret);
            if (wanted.equals(applied) && held > 0) {
                entries.add(new Entry(id, ssid, security, Reason.CURRENT,
                        STATUS_SUCCESS, recordLost));
                continue;
            }

            if (!applied.isEmpty()) {
                SuggestionRecord previous = SuggestionRecord.decode(applied);
                if (previous != null) manager.remove(id, previous);
                secrets.clear(slot);
            }

            SuggestionRecord record = SuggestionRecord.decode(wanted);
            int status = record == null ? STATUS_BUILD_FAILED : manager.add(id, record);
            if (status == STATUS_SUCCESS) {
                boolean sealed = record != null && secrets.put(slot, wanted);
                if (!sealed) {
                    entries.add(new Entry(id, ssid, security, Reason.ADDED_UNSEALED,
                            STATUS_SUCCESS, recordLost));
                } else {
                    entries.add(new Entry(id, ssid, security, Reason.ADDED,
                            STATUS_SUCCESS, recordLost));
                }
                held++;
            } else {
                Reason reason = status == STATUS_BUILD_FAILED ? Reason.BUILD_FAILED
                        : status == STATUS_REQUEST_FAILED ? Reason.REQUEST_FAILED
                        : Reason.REFUSED;
                entries.add(new Entry(id, ssid, security, reason, status, recordLost));
                overall = status;
                break;
            }
        }
        String detail = entries.isEmpty() ? "no saved network" : summary(entries);
        return new Outcome(entries, overall, detail);
    }

    /**
     * Removes one profile's suggestion and its record. The other profiles keep
     * both: their records are what Android 10 needs to remove them later.
     */
    public int removeOne(List<Profile> profiles, String id) {
        migrate(profiles);
        return removeSlot(id);
    }

    /** Removes every listed profile's suggestion, for a switch in connect mode. */
    public int removeAll(List<Profile> profiles) {
        migrate(profiles);
        int removed = 0;
        for (Profile profile : profiles) {
            removed += removeSlot(profile.id());
        }
        secrets.clear(ProfileTable.LEGACY_APPLIED_SLOT);
        return removed;
    }

    /** How many listed profiles have an applied record (the Android 10 count). */
    public int count(List<Profile> profiles) {
        migrate(profiles);
        int count = 0;
        for (Profile profile : profiles) {
            String applied = secrets.get(ProfileTable.appliedSlot(profile.id()));
            if (applied != null && !applied.isEmpty()) count++;
        }
        return count;
    }

    /**
     * Moves the pre-profile single slot to the first profile, which is the one
     * that old build applied. An existing per-profile record wins; the single
     * slot is then stale and is removed either way, so an upgrade does not
     * register the same network a second time.
     */
    public void migrate(List<Profile> profiles) {
        String legacy = secrets.get(ProfileTable.LEGACY_APPLIED_SLOT);
        if (legacy == null || legacy.isEmpty()) return;
        if (profiles.isEmpty()) {
            secrets.clear(ProfileTable.LEGACY_APPLIED_SLOT);
            return;
        }
        String slot = ProfileTable.appliedSlot(profiles.get(0).id());
        String existing = secrets.get(slot);
        if (existing == null || existing.isEmpty()) {
            if (secrets.put(slot, legacy)) {
                secrets.clear(ProfileTable.LEGACY_APPLIED_SLOT);
            }
        } else {
            secrets.clear(ProfileTable.LEGACY_APPLIED_SLOT);
        }
    }

    private int removeSlot(String id) {
        String slot = ProfileTable.appliedSlot(id);
        String applied = secrets.get(slot);
        int removed = 0;
        if (applied != null && !applied.isEmpty()) {
            SuggestionRecord record = SuggestionRecord.decode(applied);
            if (record != null) removed = manager.remove(id, record);
        }
        secrets.clear(slot);
        return removed;
    }

    private static String summary(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (out.length() > 0) out.append("; ");
            out.append("suggestion ").append(entry.id()).append(": ").append(entry.text());
        }
        return out.toString();
    }
}

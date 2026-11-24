package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-profile suggestion ledger without Android: three profiles become
 * three encrypted records, a password change rewrites only its own record, a
 * profile without a password is skipped by id, and deleting one profile leaves
 * the other suggestions and records untouched.
 *
 * <p>The fake keeps the records and the manager's held suggestions in separate
 * maps, so "the record is gone but the suggestion is still held" cannot pass by
 * accident.
 */
public final class SuggestionRegistryTest {

    private static final String CANARY = "canary-s3cret-pass";

    private SuggestionRegistryTest() {
    }

    public static void main(String[] args) {
        threeProfilesProduceThreeRecords();
        identicalRecordsAreNotRewritten();
        oneChangedPasswordRewritesOnlyItsRecord();
        aProfileWithoutAPasswordIsSkippedById();
        anOpenProfileRegistersWithoutASecret();
        deleteRemovesExactlyItsRecord();
        aFailedRemovalKeepsTheRecordForARetry();
        aRejectionStopsTheRemainingProfiles();
        theSingleSlotMigratesWithoutReregistering();
        theStatusDetailNeverCarriesAPassphrase();
        aLockedStoreWaitsInsteadOfReportingMissingPasswords();
        anUndecryptablePassphraseKeepsTheAppliedRecord();
        anUndecryptableAppliedRecordSurvivesAFailedReAdd();
        aMissingPlatformSuggestionIsRegisteredAgain();
        aFailedRemovalDuringAChangeKeepsTheRecord();
        report("SuggestionRegistryTest");
    }

    private static void threeProfilesProduceThreeRecords() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("three profiles register three suggestions", 3, manager.held.size());
        check("the first record belongs to default", record("Home", "wpa2", CANARY),
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the second record belongs to p1", record("Lab", "wpa3", CANARY + "-2"),
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
        check("the third record belongs to p2", record("Flat", "wpa2", CANARY + "-3"),
                secrets.slots.get(ProfileTable.appliedSlot("p2")));
        check("the status names every profile",
                "suggestion default: active; suggestion p1: active; suggestion p2: active",
                outcome.detail());
        check("the overall status is success", 0, outcome.status());
        check("count reports the profiles with a record", 3, registry.count(three()));
    }

    private static void identicalRecordsAreNotRewritten() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());

        Map<String, String> before = snapshot(secrets);
        manager.adds.clear();
        manager.removes.clear();
        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("an unchanged pass adds nothing", 0, manager.adds.size());
        check("an unchanged pass removes nothing", 0, manager.removes.size());
        check("every record is byte-identical after the pass", before, snapshot(secrets));
        check("every profile is reported as current", true,
                outcome.entries().get(0).reason() == SuggestionRegistry.Reason.CURRENT
                        && outcome.entries().get(1).reason()
                        == SuggestionRegistry.Reason.CURRENT
                        && outcome.entries().get(2).reason()
                        == SuggestionRegistry.Reason.CURRENT);
    }

    private static void oneChangedPasswordRewritesOnlyItsRecord() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());

        String defaultRecord = secrets.slots.get(ProfileTable.appliedSlot("default"));
        String lastRecord = secrets.slots.get(ProfileTable.appliedSlot("p2"));
        manager.adds.clear();
        manager.removes.clear();
        secrets.slots.put(ProfileTable.secretSlot("p1"), CANARY + "-new");

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("only the changed profile is added", one("p1"), manager.adds);
        check("only the changed profile is removed", one("p1"), manager.removes);
        check("the changed record carries the new passphrase",
                record("Lab", "wpa3", CANARY + "-new"),
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
        check("the other first record is byte-identical", defaultRecord,
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the other last record is byte-identical", lastRecord,
                secrets.slots.get(ProfileTable.appliedSlot("p2")));
        check("all three suggestions are still held", 3, manager.held.size());
        check("the pass still reports every profile",
                "suggestion default: active; suggestion p1: active;"
                        + " suggestion p2: active", outcome.detail());
    }

    private static void aProfileWithoutAPasswordIsSkippedById() {
        MapSecrets secrets = new MapSecrets();
        secrets.slots.put(ProfileTable.secretSlot("default"), CANARY);
        secrets.slots.put(ProfileTable.secretSlot("p1"), CANARY + "-2");
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        SuggestionRegistry.Entry skipped = entry(outcome, "p2");
        check("the passwordless profile has its own entry", "p2", skipped.id());
        check("it is skipped for a missing password", SuggestionRegistry.Reason.NO_PASSWORD,
                skipped.reason());
        check("the skip carries the no-secret status", -2, skipped.status());
        check("the skip text says a password is needed", "enter Wi-Fi password",
                skipped.text());
        checkTrue("the detail names the skipped profile",
                outcome.detail().contains("suggestion p2: enter Wi-Fi password"));
        check("the other profiles still register", 2, manager.adds.size());
        check("the passwordless profile has no suggestion", false,
                manager.held.containsKey("p2"));
        check("the passwordless profile has no record", null,
                secrets.slots.get(ProfileTable.appliedSlot("p2")));
        check("the skip is visible in the overall status", -2, outcome.status());
    }

    private static void anOpenProfileRegistersWithoutASecret() {
        MapSecrets secrets = new MapSecrets();
        // A stale secret must not turn an open AP into a WPA2 one.
        secrets.slots.put(ProfileTable.secretSlot("default"), CANARY);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome outcome = registry.addAll(Arrays.asList(
                new Profile("default", "Cafe", ProfileTable.SECURITY_OPEN)));

        check("an open profile registers without a password", 1, manager.held.size());
        check("its record has an empty passphrase", record("Cafe", "open", ""),
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the open profile is active", 0, outcome.status());
    }

    private static void deleteRemovesExactlyItsRecord() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());
        String defaultRecord = secrets.slots.get(ProfileTable.appliedSlot("default"));
        String lastRecord = secrets.slots.get(ProfileTable.appliedSlot("p2"));
        manager.removes.clear();

        int removed = registry.removeOne(three(), "p1");

        check("delete removes one suggestion", 1, removed);
        check("delete removed only p1 from the manager", one("p1"), manager.removes);
        check("delete clears only its own record", false,
                secrets.slots.containsKey(ProfileTable.appliedSlot("p1")));
        check("the other first record is untouched", defaultRecord,
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the other last record is untouched", lastRecord,
                secrets.slots.get(ProfileTable.appliedSlot("p2")));
        check("two suggestions remain registered", 2, registry.count(three()));
        check("the deleted suggestion is no longer held", false,
                manager.held.containsKey("p1"));
    }

    private static void aFailedRemovalKeepsTheRecordForARetry() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());
        String record = secrets.slots.get(ProfileTable.appliedSlot("p1"));
        manager.failRemove = true;

        int failed = registry.removeOne(three(), "p1");

        check("a failed removal reports the request failure",
                SuggestionRegistry.STATUS_REQUEST_FAILED, failed);
        check("a failed removal keeps the applied record for a retry",
                record, secrets.slots.get(ProfileTable.appliedSlot("p1")));
        check("a failed removal keeps the platform suggestion", true,
                manager.held.containsKey("p1"));

        manager.failRemove = false;
        int retried = registry.removeOne(three(), "p1");

        check("the retry removes the suggestion", 1, retried);
        check("the retry clears the applied record", false,
                secrets.slots.containsKey(ProfileTable.appliedSlot("p1")));
        check("the retry removes the platform suggestion", false,
                manager.held.containsKey("p1"));
    }

    private static void aRejectionStopsTheRemainingProfiles() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        manager.rejectSsid = "Lab";
        manager.rejectStatus = 2;
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("the rejection stops before the third profile", 2, manager.adds.size());
        check("the rejected profile is reported as refused", SuggestionRegistry.Reason.REFUSED,
                entry(outcome, "p1").reason());
        check("the rejection keeps Android's status", 2, entry(outcome, "p1").status());
        check("the rejected profile has no record", null,
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
        checkTrue("the detail stops at the rejected profile",
                outcome.detail().endsWith("suggestion p1: status 2"));
        check("the overall status is the rejection", 2, outcome.status());
    }

    private static void theSingleSlotMigratesWithoutReregistering() {
        MapSecrets secrets = new MapSecrets();
        secrets.slots.put(ProfileTable.secretSlot("default"), CANARY);
        secrets.slots.put(ProfileTable.secretSlot("p1"), CANARY + "-2");
        String legacy = record("Home", "wpa2", CANARY);
        secrets.slots.put(ProfileTable.LEGACY_APPLIED_SLOT, legacy);
        FakeManager manager = new FakeManager();
        // Android 10 still holds the suggestion the old single slot described.
        manager.held.put("default", legacy);
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        List<Profile> profiles = Arrays.asList(
                new Profile("default", "Home", "wpa2"),
                new Profile("p1", "Lab", "wpa3"));

        SuggestionRegistry.Outcome outcome = registry.addAll(profiles);

        check("the single slot moves to the first profile", legacy,
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the single slot is cleared after the move", false,
                secrets.slots.containsKey(ProfileTable.LEGACY_APPLIED_SLOT));
        check("the existing suggestion is not registered again", one("p1"), manager.adds);
        check("the migrated profile is reported as current",
                SuggestionRegistry.Reason.CURRENT, entry(outcome, "default").reason());
        check("both profiles end up registered", 2, manager.held.size());
    }

    private static void theStatusDetailNeverCarriesAPassphrase() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        checkTrue("the status detail carries no passphrase",
                !outcome.detail().contains(CANARY));
        for (SuggestionRegistry.Entry entry : outcome.entries()) {
            checkTrue("profile " + entry.id() + " status carries no passphrase",
                    !entry.text().contains(CANARY) && !entry.ssid().contains(CANARY));
        }
    }

    private static void aLockedStoreWaitsInsteadOfReportingMissingPasswords() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        secrets.locked = true;
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        Map<String, String> before = snapshot(secrets);

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("a locked store is its own status", SuggestionRegistry.STATUS_STORAGE_LOCKED,
                outcome.status());
        check("the status is retryable after unlock", true, outcome.retryable());
        check("no profile is falsely reported as missing its password", true,
                outcome.entries().isEmpty());
        check("the detail says it waits for unlock", "suggestion: waiting for unlock",
                outcome.detail());
        check("nothing is added before unlock", 0, manager.adds.size());
        check("no stored record is cleared before unlock", before, snapshot(secrets));
        check("the manager is not asked for a count", 0, manager.counts);
    }

    private static void anUndecryptablePassphraseKeepsTheAppliedRecord() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());
        String applied = secrets.slots.get(ProfileTable.appliedSlot("p1"));
        secrets.unreadable.add(ProfileTable.secretSlot("p1"));
        manager.adds.clear();
        manager.removes.clear();

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        SuggestionRegistry.Entry entry = entry(outcome, "p1");
        check("an undecryptable passphrase has its own reason",
                SuggestionRegistry.Reason.DECRYPT_FAILED, entry.reason());
        check("it is reported as a missing secret", SuggestionRegistry.STATUS_NO_SECRET,
                entry.status());
        check("its text says the passphrase is unreadable", "passphrase unreadable",
                entry.text());
        check("the applied record survives the decrypt failure", applied,
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
        check("the old suggestion is not removed", false, manager.removes.contains("p1"));
        check("no suggestion is added with an empty password", false,
                manager.adds.contains("p1"));
        check("the other profiles remain current", SuggestionRegistry.Reason.CURRENT,
                entry(outcome, "default").reason());
    }

    private static void anUndecryptableAppliedRecordSurvivesAFailedReAdd() {
        MapSecrets secrets = new MapSecrets();
        secrets.slots.put(ProfileTable.secretSlot("default"), CANARY);
        String opaque = "opaque-ciphertext-that-cannot-be-decrypted";
        secrets.slots.put(ProfileTable.appliedSlot("default"), opaque);
        secrets.unreadable.add(ProfileTable.appliedSlot("default"));
        FakeManager manager = new FakeManager();
        manager.rejectSsid = "Home";
        manager.rejectStatus = 2;
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);

        SuggestionRegistry.Outcome failed = registry.addAll(Arrays.asList(
                new Profile("default", "Home", "wpa2")));

        check("an undecryptable record is noticed", true,
                entry(failed, "default").recordLost());
        check("a rejected re-add reports Android's status", 2, failed.status());
        check("the undecryptable record is not deleted", opaque,
                secrets.slots.get(ProfileTable.appliedSlot("default")));

        manager.rejectSsid = null;
        SuggestionRegistry.Outcome retried = registry.addAll(Arrays.asList(
                new Profile("default", "Home", "wpa2")));

        check("a later successful add replaces the record",
                record("Home", "wpa2", CANARY),
                secrets.slots.get(ProfileTable.appliedSlot("default")));
        check("the successful retry is reported as added",
                SuggestionRegistry.Reason.ADDED, entry(retried, "default").reason());
    }

    private static void aMissingPlatformSuggestionIsRegisteredAgain() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        manager.reportSsids = true;
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());

        // The records survived but the platform no longer holds the suggestions
        // (the reboot case the field report describes).
        manager.held.clear();
        manager.adds.clear();
        manager.removes.clear();
        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("a missing suggestion is re-registered", 3, manager.adds.size());
        check("all three are held again", 3, manager.held.size());
        check("the pass still succeeds", 0, outcome.status());
        check("the reason names the missing suggestion",
                SuggestionRegistry.Reason.READDED, entry(outcome, "p1").reason());
        check("the text stays active", "active", entry(outcome, "p1").text());
        check("the matching record is still byte-identical",
                record("Home", "wpa2", CANARY),
                secrets.slots.get(ProfileTable.appliedSlot("default")));
    }

    private static void aFailedRemovalDuringAChangeKeepsTheRecord() {
        MapSecrets secrets = new MapSecrets();
        seedThree(secrets);
        FakeManager manager = new FakeManager();
        SuggestionRegistry registry = new SuggestionRegistry(secrets, manager);
        registry.addAll(three());
        String applied = secrets.slots.get(ProfileTable.appliedSlot("p1"));
        secrets.slots.put(ProfileTable.secretSlot("p1"), CANARY + "-new");
        manager.failRemove = true;
        manager.adds.clear();

        SuggestionRegistry.Outcome outcome = registry.addAll(three());

        check("a failed removal stops the changed profile",
                SuggestionRegistry.STATUS_REQUEST_FAILED, outcome.status());
        check("the changed profile reports the request failure",
                SuggestionRegistry.Reason.REQUEST_FAILED, entry(outcome, "p1").reason());
        check("its old record is kept for a retry", applied,
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
        check("no second suggestion is added for the same SSID", 0, manager.adds.size());
        check("the old suggestion is still held", true, manager.held.containsKey("p1"));

        manager.failRemove = false;
        SuggestionRegistry.Outcome retried = registry.addAll(three());
        check("the retry re-registers the changed profile", 0, retried.status());
        check("its record now carries the new passphrase",
                record("Lab", "wpa3", CANARY + "-new"),
                secrets.slots.get(ProfileTable.appliedSlot("p1")));
    }

    private static List<Profile> three() {
        return Arrays.asList(
                new Profile("default", "Home", "wpa2"),
                new Profile("p1", "Lab", "wpa3"),
                new Profile("p2", "Flat", "wpa2"));
    }

    private static void seedThree(MapSecrets secrets) {
        secrets.slots.put(ProfileTable.secretSlot("default"), CANARY);
        secrets.slots.put(ProfileTable.secretSlot("p1"), CANARY + "-2");
        secrets.slots.put(ProfileTable.secretSlot("p2"), CANARY + "-3");
    }

    private static String record(String ssid, String security, String pass) {
        return SuggestionRecord.encode(ssid, security, pass);
    }

    private static List<String> one(String id) {
        List<String> expected = new ArrayList<>();
        expected.add(id);
        return expected;
    }

    private static SuggestionRegistry.Entry entry(SuggestionRegistry.Outcome outcome, String id) {
        for (SuggestionRegistry.Entry entry : outcome.entries()) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

    /** The encrypted slots copied for a byte-for-byte comparison. */
    private static Map<String, String> snapshot(MapSecrets secrets) {
        return new LinkedHashMap<>(secrets.slots);
    }

    /** PassphraseStore's encrypted slots, in a map a test can inspect. */
    private static final class MapSecrets implements SuggestionRegistry.Secrets {
        final Map<String, String> slots = new LinkedHashMap<>();
        /** Slots whose ciphertext the Keystore cannot decrypt (get returns null). */
        final java.util.Set<String> unreadable = new java.util.HashSet<>();
        /** True before the first unlock: nothing in this store is readable. */
        boolean locked;

        @Override
        public String get(String slot) {
            if (unreadable.contains(slot)) return null;
            // PassphraseStore returns "" for an absent slot, so the fake must
            // too: null is reserved for "cannot decrypt".
            return slots.containsKey(slot) ? slots.get(slot) : "";
        }

        @Override
        public boolean put(String slot, String value) {
            if (value == null || value.isEmpty()) {
                slots.remove(slot);
            } else {
                slots.put(slot, value);
            }
            return true;
        }

        @Override
        public void clear(String slot) {
            slots.remove(slot);
        }

        @Override
        public boolean locked() {
            return locked;
        }
    }

    /** The suggestion manager: records what Android would hold, by profile id. */
    private static final class FakeManager implements SuggestionRegistry.Manager {
        final Map<String, String> held = new LinkedHashMap<>();
        final List<String> adds = new ArrayList<>();
        final List<String> removes = new ArrayList<>();
        String rejectSsid;
        int rejectStatus;
        boolean failRemove;
        /** Whether this fake can enumerate held SSIDs (the API 30+ answer). */
        boolean reportSsids;
        int counts;

        @Override
        public int add(String id, SuggestionRecord record) {
            adds.add(id);
            if (rejectSsid != null && rejectSsid.equals(record.ssid())) {
                return rejectStatus;
            }
            held.put(id, record.encode());
            return SuggestionRegistry.STATUS_SUCCESS;
        }

        @Override
        public int remove(String id, SuggestionRecord record) {
            removes.add(id);
            if (failRemove) return SuggestionRegistry.STATUS_REQUEST_FAILED;
            held.remove(id);
            return 1;
        }

        @Override
        public int count() {
            counts++;
            // -1 is the Android 10 answer: only the records can count.
            return reportSsids ? held.size() : -1;
        }

        @Override
        public java.util.Set<String> heldSsids() {
            if (!reportSsids) return null;
            return new java.util.LinkedHashSet<>(held.keySet());
        }
    }
}

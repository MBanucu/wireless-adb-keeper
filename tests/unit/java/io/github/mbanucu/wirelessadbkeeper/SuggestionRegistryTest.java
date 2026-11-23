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

        @Override
        public String get(String slot) {
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
    }

    /** The suggestion manager: records what Android would hold, by profile id. */
    private static final class FakeManager implements SuggestionRegistry.Manager {
        final Map<String, String> held = new LinkedHashMap<>();
        final List<String> adds = new ArrayList<>();
        final List<String> removes = new ArrayList<>();
        String rejectSsid;
        int rejectStatus;
        boolean failRemove;

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
            // -1 is the Android 10 answer: only the records can count.
            return -1;
        }
    }
}

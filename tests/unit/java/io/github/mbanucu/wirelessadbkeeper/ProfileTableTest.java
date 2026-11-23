package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The saved-network store without Android: the legacy migration, the one-per-
 * SSID rule, the cap, id stability and deletion of the secret plus the applied
 * record. The fake keeps prefs and secrets in separate maps, which is also how
 * the "no passphrase outside PassphraseStore" check is possible.
 */
public final class ProfileTableTest {

    private ProfileTableTest() {
    }

    public static void main(String[] args) {
        migrationMovesTheLegacyNetworkToDefault();
        migrationWithoutALegacyNetworkDoesNothing();
        capRefusesTheSeventeenthAndKeepsTheSixteen();
        saveBySsidUpdatesInsteadOfDuplicating();
        renameKeepsTheIdAndRejectsADuplicateTarget();
        deleteRemovesTheSecretAndTheAppliedRecord();
        deleteUnknownIsANamedError();
        securityIsNormalizedAndValidated();
        aFailedSecretWriteLeavesTheStoreAlone();
        idsAreOpaqueAndOrdered();
        noPassphraseIsWrittenToPrefs();
        report("ProfileTableTest");
    }

    private static void migrationMovesTheLegacyNetworkToDefault() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        store.values.put(ProfileTable.LEGACY_SSID_KEY, "Home");
        secrets.legacy = "secret";

        ProfileTable table = new ProfileTable(store, secrets);
        List<Profile> list = table.list();

        check("migration yields exactly one profile", 1, list.size());
        check("the migrated id is default", "default", list.get(0).id());
        check("the migrated SSID is the legacy one", "Home", list.get(0).ssid());
        check("the migrated security is auto", "", list.get(0).security());
        check("the migrated secret is in profile.default", "secret",
                secrets.slots.get(ProfileTable.secretSlot("default")));
        check("the legacy SSID key is gone", null,
                store.values.get(ProfileTable.LEGACY_SSID_KEY));
        check("the ordered id list starts with default", "default",
                store.values.get(ProfileTable.IDS_KEY));
        check("the legacy secret is gone from the secret adapter", "",
                secrets.legacy);
        check("a second read does not duplicate", 1, table.list().size());
        check("listing twice writes the migration once", 1, store.writes);
    }

    private static void migrationWithoutALegacyNetworkDoesNothing() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        check("a fresh install has no profiles", 0, table.list().size());
        check("a fresh install does not write a marker", null,
                store.values.get(ProfileTable.IDS_KEY));
        check("a fresh install has no active profile", null, table.active());
    }

    private static void capRefusesTheSeventeenthAndKeepsTheSixteen() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        for (int i = 1; i <= ProfileTable.CAPACITY; i++) {
            ProfileTable.Outcome outcome = table.add("Net" + i, null, "pass" + i);
            checkTrue("add " + i + " succeeds", outcome.ok());
        }
        check("the cap is 16", 16, ProfileTable.CAPACITY);
        check("sixteen profiles are stored", 16, table.count());
        String idsBefore = store.values.get(ProfileTable.IDS_KEY);

        ProfileTable.Outcome full = table.add("Net17", null, "pass17");
        check("the seventeenth add is refused", false, full.ok());
        check("the refusal is a named error", "profile limit is 16", full.error());
        check("sixteen profiles survive the refusal", 16, table.count());
        check("the id list is unchanged by the refusal", idsBefore,
                store.values.get(ProfileTable.IDS_KEY));
        check("the refused SSID was not stored", null, table.bySsid("Net17"));
        check("the refused secret was not stored", null,
                secrets.slots.get(ProfileTable.secretSlot("p17")));
    }

    private static void saveBySsidUpdatesInsteadOfDuplicating() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        ProfileTable.Outcome first = table.save("Home", null, "one");
        ProfileTable.Outcome second = table.save("Home", "wpa3", "two");

        check("the first save succeeds", true, first.ok());
        check("the second save succeeds", true, second.ok());
        check("the same SSID keeps the same id", first.profile().id(),
                second.profile().id());
        check("the same SSID is not duplicated", 1, table.count());
        check("the later secret replaces the earlier one", "two",
                secrets.slots.get(ProfileTable.secretSlot(first.profile().id())));
        check("the later security replaces the earlier one", "wpa3",
                table.bySsid("Home").security());
    }

    private static void renameKeepsTheIdAndRejectsADuplicateTarget() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        ProfileTable.Outcome home = table.save("Home", "wpa2", "one");
        ProfileTable.Outcome lab = table.save("Lab", "wpa3", "two");
        ProfileTable.Outcome renamed = table.update(home.profile().id(), "Flat",
                null, null);

        check("a rename succeeds", true, renamed.ok());
        check("a rename keeps the id", home.profile().id(), renamed.profile().id());
        check("a rename keeps the secret", "one",
                secrets.slots.get(ProfileTable.secretSlot(home.profile().id())));
        check("a rename keeps the security when none is given", "wpa2",
                renamed.profile().security());
        check("a rename does not add a profile", 2, table.count());
        check("the old SSID is no longer found", null, table.bySsid("Home"));

        ProfileTable.Outcome duplicate = table.update(home.profile().id(), "Lab",
                null, null);
        check("a rename onto another profile is refused", false, duplicate.ok());
        check("the duplicate rename is named", "another profile already uses this SSID",
                duplicate.error());
        check("the refused rename leaves the SSID alone", "Flat",
                table.bySsid("Flat").ssid());
        check("the refused rename leaves two profiles", 2, table.count());
        check("the other profile still exists", lab.profile().id(),
                table.bySsid("Lab").id());
    }

    private static void deleteRemovesTheSecretAndTheAppliedRecord() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        ProfileTable.Outcome home = table.save("Home", null, "secret");
        String id = home.profile().id();
        secrets.slots.put(ProfileTable.secretSlot(id), "secret");
        secrets.slots.put(ProfileTable.appliedSlot(id), "applied record");
        secrets.slots.put(ProfileTable.LEGACY_APPLIED_SLOT, "legacy applied record");

        ProfileTable.Outcome removed = table.remove(id);

        check("delete succeeds", true, removed.ok());
        check("delete returns the removed profile", "Home", removed.profile().ssid());
        check("delete leaves no profiles", 0, table.count());
        check("delete removes the secret slot", false,
                secrets.slots.containsKey(ProfileTable.secretSlot(id)));
        check("delete removes the per-profile applied record", false,
                secrets.slots.containsKey(ProfileTable.appliedSlot(id)));
        check("delete of the first profile removes the legacy applied record", false,
                secrets.slots.containsKey(ProfileTable.LEGACY_APPLIED_SLOT));
        check("delete removes the SSID key", null,
                store.values.get(ProfileTable.ssidKey(id)));
        check("delete removes the security key", null,
                store.values.get(ProfileTable.securityKey(id)));
        check("delete leaves an empty id list marker", "",
                store.values.get(ProfileTable.IDS_KEY));
    }

    private static void deleteUnknownIsANamedError() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);
        table.save("Home", null, "secret");

        ProfileTable.Outcome removed = table.remove("nope");
        check("deleting an unknown id fails", false, removed.ok());
        check("the unknown delete is named", "no such profile", removed.error());
        check("the store is untouched by an unknown delete", 1, table.count());
    }

    private static void securityIsNormalizedAndValidated() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        table.save("Home", "AUTO", null);
        check("auto normalizes to empty", "", table.bySsid("Home").security());
        table.save("Home", "WPA3", null);
        check("a security word is lowercased", "wpa3", table.bySsid("Home").security());
        table.save("Home", "owe", null);
        check("owe is stored", "owe", table.bySsid("Home").security());

        ProfileTable.Outcome invalid = table.save("Home", "wep", null);
        check("an unknown security is refused", false, invalid.ok());
        check("the security refusal is named",
                "security must be auto, wpa2, wpa3, owe or open", invalid.error());
        check("the refused security leaves the old value", "owe",
                table.bySsid("Home").security());
    }

    private static void aFailedSecretWriteLeavesTheStoreAlone() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);
        table.save("Home", null, "old");

        secrets.failPut = true;
        ProfileTable.Outcome failed = table.save("Home", null, "new");
        check("a failed secret write fails the save", false, failed.ok());
        check("the encryption failure is named", "could not encrypt the passphrase",
                failed.error());
        check("a failed secret write keeps the old secret", "old",
                secrets.slots.get(ProfileTable.secretSlot("p1")));
        check("a failed secret write does not add a profile", 1, table.count());

        ProfileTable.Outcome added = table.add("Other", null, "x");
        check("a failed secret write on add is refused", false, added.ok());
        check("the refused add leaves the list alone", 1, table.count());
    }

    private static void idsAreOpaqueAndOrdered() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        store.values.put(ProfileTable.LEGACY_SSID_KEY, "Home");
        secrets.legacy = "secret";
        ProfileTable table = new ProfileTable(store, secrets);

        table.save("Lab", null, "two");
        table.save("Flat", null, "three");
        check("the migrated default stays first", "default,p1,p2",
                store.values.get(ProfileTable.IDS_KEY));
        check("ids are opaque, not SSIDs", "p1", table.list().get(1).id());
    }

    private static void assertNoValueContains(Map<String, String> values, String secret) {
        List<String> leaks = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (String.valueOf(entry.getValue()).contains(secret)) {
                leaks.add(entry.getKey());
            }
        }
        check("no passphrase is stored in prefs", new ArrayList<String>(), leaks);
    }

    private static void noPassphraseIsWrittenToPrefs() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);

        String secret = "s3cret-passphrase";
        table.save("Home", "wpa2", secret);
        table.save("Lab", "wpa3", secret + "-2");
        table.update("p1", "Flat", null, secret);

        check("the passphrase is in the secret store", secret,
                secrets.slots.get(ProfileTable.secretSlot("p1")));
        assertNoValueContains(store.values, secret);
        boolean secretKey = false;
        for (String key : store.values.keySet()) {
            if (key.contains("passphrase") || key.endsWith("_iv")
                    || key.endsWith("_ciphertext")) {
                secretKey = true;
            }
        }
        checkTrue("prefs hold no secret-shaped key", !secretKey);
    }

    /** Prefs, with every commit counted so a migration can be checked. */
    private static final class MapStore implements ProfileTable.Store {
        final Map<String, String> values = new LinkedHashMap<>();
        int writes;

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void write(Map<String, String> puts, Set<String> removes) {
            writes++;
            for (String key : removes) values.remove(key);
            values.putAll(puts);
        }
    }

    /** PassphraseStore, kept apart from the prefs map on purpose. */
    private static final class MapSecrets implements ProfileTable.Secrets {
        final Map<String, String> slots = new LinkedHashMap<>();
        String legacy = "";
        boolean failPut;

        @Override
        public boolean put(String slot, String value) {
            if (failPut) return false;
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
        public boolean adoptLegacy(String slot) {
            if (failPut) return false;
            if (!legacy.isEmpty() && !slots.containsKey(slot)) {
                slots.put(slot, legacy);
            }
            legacy = "";
            return true;
        }
    }
}

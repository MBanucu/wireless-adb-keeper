package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Network page's row model without Android: try order, the active marker,
 * the move arrows, the security badge, the password/suggestion labels and the
 * defined empty state after deleting the last profile.
 */
public final class ProfileRowTest {

    private ProfileRowTest() {
    }

    public static void main(String[] args) {
        rowsFollowTryOrderAndMarkOnlyTheFirstActive();
        movingChangesTheActiveMarkerAndTheArrows();
        badgesAndLabelsDescribePasswordAndLastSuggestion();
        editingOneProfileLeavesTheOtherSecretAlone();
        aNewTableReadsEveryChangeBack();
        deletingTheLastProfileLeavesTheEmptyState();
        aMissingLookupNeverInventsAPassword();
        report("ProfileRowTest");
    }

    private static void rowsFollowTryOrderAndMarkOnlyTheFirstActive() {
        ProfileTable table = table();
        table.save("Home", "wpa2", "one");
        table.save("Lab", "wpa3", "two");
        List<ProfileRow> rows = ProfileRow.rows(table.list(), lookup());

        check("two saved networks make two rows", 2, rows.size());
        check("the first row is the first saved id", "p1", rows.get(0).id());
        check("the second row is the second saved id", "p2", rows.get(1).id());
        check("positions are 1-based", 1, rows.get(0).position());
        check("the second position is 2", 2, rows.get(1).position());
        check("only the first row is active", true, rows.get(0).active());
        check("the second row is not active", false, rows.get(1).active());
        check("the first row cannot move up", false, rows.get(0).canMoveUp());
        check("the first row can move down", true, rows.get(0).canMoveDown());
        check("the last row can move up", true, rows.get(1).canMoveUp());
        check("the last row cannot move down", false, rows.get(1).canMoveDown());
    }

    private static void movingChangesTheActiveMarkerAndTheArrows() {
        ProfileTable table = table();
        table.save("Home", null, "one");
        table.save("Lab", null, "two");
        ProfileTable.Outcome moved = table.move("p2", 1);
        checkTrue("the move succeeds", moved.ok());

        List<ProfileRow> rows = ProfileRow.rows(table.list(), lookup());
        check("the moved profile is first", "p2", rows.get(0).id());
        check("the moved profile is active", true, rows.get(0).active());
        check("the old first profile is second", "p1", rows.get(1).id());
        check("the old first profile is no longer active", false, rows.get(1).active());
        check("the new first row cannot move up", false, rows.get(0).canMoveUp());
        check("the new first row can move down", true, rows.get(0).canMoveDown());
        check("the displaced row can move up", true, rows.get(1).canMoveUp());
        check("a two-row list still has one last row", false, rows.get(1).canMoveDown());
    }

    private static void badgesAndLabelsDescribePasswordAndLastSuggestion() {
        Map<String, String> security = new LinkedHashMap<>();
        security.put("auto", "");
        security.put("wpa2", ProfileTable.SECURITY_WPA2);
        security.put("wpa3", ProfileTable.SECURITY_WPA3);
        security.put("owe", ProfileTable.SECURITY_OWE);
        security.put("open", ProfileTable.SECURITY_OPEN);
        java.util.List<Profile> profiles = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : security.entrySet()) {
            profiles.add(new Profile(entry.getKey(), entry.getKey(), entry.getValue()));
        }

        Map<String, ProfileRow.Password> passwords = new LinkedHashMap<>();
        passwords.put("auto", ProfileRow.Password.SAVED);
        passwords.put("wpa2", ProfileRow.Password.NONE);
        passwords.put("wpa3", ProfileRow.Password.UNREADABLE);
        passwords.put("owe", ProfileRow.Password.SAVED);
        passwords.put("open", ProfileRow.Password.NONE);
        Map<String, ProfileRow.Suggestion> suggestions = new LinkedHashMap<>();
        suggestions.put("auto", ProfileRow.Suggestion.APPLIED);
        suggestions.put("wpa2", ProfileRow.Suggestion.NONE);
        suggestions.put("wpa3", ProfileRow.Suggestion.NONE);
        suggestions.put("owe", ProfileRow.Suggestion.APPLIED);
        suggestions.put("open", ProfileRow.Suggestion.NONE);

        List<ProfileRow> rows = ProfileRow.rows(profiles, new ProfileRow.Lookup() {
            @Override
            public ProfileRow.Password password(String id) {
                return passwords.get(id);
            }

            @Override
            public ProfileRow.Suggestion suggestion(String id) {
                return suggestions.get(id);
            }
        });

        check("the auto badge", "auto", rows.get(0).securityBadge());
        check("the WPA2 badge", "WPA2", rows.get(1).securityBadge());
        check("the WPA3 badge", "WPA3", rows.get(2).securityBadge());
        check("the OWE badge", "OWE", rows.get(3).securityBadge());
        check("the open badge", "open", rows.get(4).securityBadge());

        check("a saved password is a label, not a value", "password saved",
                rows.get(0).passwordLabel());
        check("an absent password is named", "no password", rows.get(1).passwordLabel());
        check("an unreadable password is named", "password unreadable",
                rows.get(2).passwordLabel());
        check("an applied suggestion is named", "suggestion applied",
                rows.get(0).suggestionLabel());
        check("a missing suggestion is named", "suggestion not applied",
                rows.get(1).suggestionLabel());
        check("the row summary joins both states", "password saved · suggestion applied",
                rows.get(0).summary());
        check("the editor hint says a password is saved",
                "Saved - type a new password to replace",
                ProfileRow.Password.SAVED.hint());
        check("the editor hint says no password is saved",
                "Password (no password saved)", ProfileRow.Password.NONE.hint());
    }

    private static void editingOneProfileLeavesTheOtherSecretAlone() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);
        table.save("Home", "wpa2", "home-secret");
        table.save("Lab", "wpa3", "lab-secret");

        ProfileTable.Outcome edited = table.update("p1", "Home", null, "new-secret");
        checkTrue("editing one profile succeeds", edited.ok());
        check("the edited profile keeps its id", "p1", edited.profile().id());
        check("the edited secret is the new one", "new-secret",
                secrets.slots.get(ProfileTable.secretSlot("p1")));
        check("the other profile's secret is untouched", "lab-secret",
                secrets.slots.get(ProfileTable.secretSlot("p2")));
        check("the other profile's SSID is untouched", "Lab", table.bySsid("Lab").ssid());
        check("editing one profile does not add a row", 2, table.count());
    }

    private static void aNewTableReadsEveryChangeBack() {
        MapStore store = new MapStore();
        MapSecrets secrets = new MapSecrets();
        ProfileTable table = new ProfileTable(store, secrets);
        table.save("Home", "wpa2", "home-secret");
        table.save("Lab", "wpa3", "lab-secret");
        table.update("p1", "Home", null, "new-secret");
        table.move("p2", 1);

        ProfileTable restarted = new ProfileTable(store, secrets);
        List<Profile> list = restarted.list();
        check("a restart keeps both profiles", 2, list.size());
        check("a restart keeps the new try order", "p2", list.get(0).id());
        check("a restart keeps the moved network first", "Lab", list.get(0).ssid());
        check("a restart keeps the edited password", "new-secret",
                secrets.slots.get(ProfileTable.secretSlot("p1")));
        check("a restart keeps the other password", "lab-secret",
                secrets.slots.get(ProfileTable.secretSlot("p2")));
        check("the edited row is second after a restart", "Home", list.get(1).ssid());
        List<ProfileRow> rows = ProfileRow.rows(list, lookup());
        check("the moved row is the active one after a restart", true,
                rows.get(0).active());
        check("the edited row is not active after a restart", false,
                rows.get(1).active());
    }

    private static void deletingTheLastProfileLeavesTheEmptyState() {
        ProfileTable table = table();
        ProfileTable.Outcome saved = table.save("Only", null, "secret");
        check("the only profile is active", true,
                ProfileRow.rows(table.list(), lookup()).get(0).active());

        ProfileTable.Outcome removed = table.remove(saved.profile().id());
        checkTrue("deleting the only profile succeeds", removed.ok());
        check("no rows remain", 0, ProfileRow.rows(table.list(), lookup()).size());
        check("no profiles remain", 0, table.count());
        check("the empty state has a defined title", "No network saved",
                ProfileRow.EMPTY_TITLE);
        checkTrue("the empty state explains what to do",
                ProfileRow.EMPTY_DETAIL.contains("Add a network"));
    }

    private static void aMissingLookupNeverInventsAPassword() {
        List<Profile> profiles = new java.util.ArrayList<>();
        profiles.add(new Profile("p1", "Home", "wpa2"));
        List<ProfileRow> rows = ProfileRow.rows(profiles, null);
        check("a missing lookup means no password", "no password",
                rows.get(0).passwordLabel());
        check("a missing lookup means no suggestion", "suggestion not applied",
                rows.get(0).suggestionLabel());
    }

    private static ProfileRow.Lookup lookup() {
        return new ProfileRow.Lookup() {
            @Override
            public ProfileRow.Password password(String id) {
                return ProfileRow.Password.SAVED;
            }

            @Override
            public ProfileRow.Suggestion suggestion(String id) {
                return ProfileRow.Suggestion.NONE;
            }
        };
    }

    private static ProfileTable table() {
        return new ProfileTable(new MapStore(), new MapSecrets());
    }

    private static final class MapStore implements ProfileTable.Store {
        final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void write(Map<String, String> puts, Set<String> removes) {
            for (String key : removes) values.remove(key);
            values.putAll(puts);
        }
    }

    private static final class MapSecrets implements ProfileTable.Secrets {
        final Map<String, String> slots = new LinkedHashMap<>();

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
        public boolean adoptLegacy(String slot) {
            return true;
        }
    }
}

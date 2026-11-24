package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Network page's words without Android: one row per profile in try order,
 * the active marker, the security badge, the password state, the last
 * suggestion status, the summary and the delete confirmation.
 */
public final class NetworkRowsTest {

    private NetworkRowsTest() {
    }

    public static void main(String[] args) {
        emptyListIsADefinedState();
        oneProfileShowsEveryLabel();
        manyProfilesKeepTheirOrderAndOnlyTheFirstIsActive();
        securityBadgesCoverTheFiveWords();
        passwordStateComesFromTheSecretIds();
        suggestionStatusIsParsedPerProfile();
        suggestionStatusWithoutAPassIsDefined();
        summaryNamesTheFirstNetwork();
        deleteConfirmationNamesTheSsidAndThePassword();
        report("NetworkRowsTest");
    }

    private static List<Profile> profiles(Profile... profiles) {
        return new ArrayList<>(Arrays.asList(profiles));
    }

    private static Set<String> secrets(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static void emptyListIsADefinedState() {
        List<NetworkRows.Row> rows = NetworkRows.rows(
                profiles(), secrets(), new LinkedHashMap<String, String>());

        check("an empty store has no rows", 0, rows.size());
        check("the empty summary is defined", "No network saved",
                NetworkRows.summary(rows));
        check("the empty summary is the exported constant", NetworkRows.EMPTY_SUMMARY,
                NetworkRows.summary(rows));
    }

    private static void oneProfileShowsEveryLabel() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("p1", "active");
        List<NetworkRows.Row> rows = NetworkRows.rows(
                profiles(new Profile("p1", "Home", "")), secrets("p1"), statuses);
        NetworkRows.Row row = rows.get(0);

        check("one profile is one row", 1, rows.size());
        check("the row keeps the id", "p1", row.id());
        check("the row shows the SSID", "Home", row.ssid());
        check("the only profile is active", true, row.active());
        check("the row marks the active profile", "ACTIVE - TRIED FIRST", row.marker());
        check("auto is the badge for an unset security", "auto", row.badge());
        check("the row says a password is saved", "password saved", row.passwordLabel());
        check("the row shows the suggestion status", "suggestion: active", row.statusLabel());
        check("the subtitle joins the labels", "auto | password saved | suggestion: active",
                row.subtitle());
        check("the row summary names the SSID", "Home - auto | password saved"
                + " | suggestion: active", row.summary());
        check("the one-profile summary names the first network",
                "1 network saved. Home is tried first.", NetworkRows.summary(rows));
    }

    private static void manyProfilesKeepTheirOrderAndOnlyTheFirstIsActive() {
        List<NetworkRows.Row> rows = NetworkRows.rows(
                profiles(new Profile("p1", "Home", "wpa2"),
                        new Profile("p2", "Lab", "wpa3"),
                        new Profile("p3", "Cafe", "open")),
                secrets("p1"), new LinkedHashMap<String, String>());

        check("all profiles become rows", 3, rows.size());
        check("the first row is the first profile", "Home", rows.get(0).ssid());
        check("the second row keeps the try order", "Lab", rows.get(1).ssid());
        check("the third row keeps the try order", "Cafe", rows.get(2).ssid());
        check("only the first row is active", true, rows.get(0).active());
        check("the second row is not active", false, rows.get(1).active());
        check("the third row is not active", false, rows.get(2).active());
        checkTrue("the active marker is on the first row only",
                !rows.get(0).marker().isEmpty() && rows.get(1).marker().isEmpty()
                        && rows.get(2).marker().isEmpty());
        check("the first row has a password", "password saved",
                rows.get(0).passwordLabel());
        check("the other rows have no password", "no password",
                rows.get(1).passwordLabel());
        check("the multi-profile summary names the count and the first network",
                "3 networks saved. Home is tried first.", NetworkRows.summary(rows));
    }

    private static void securityBadgesCoverTheFiveWords() {
        check("null is auto", "auto", NetworkRows.securityBadge(null));
        check("empty is auto", "auto", NetworkRows.securityBadge(""));
        check("the word auto stays auto", "auto", NetworkRows.securityBadge("auto"));
        check("wpa2 is an upper-case badge", "WPA2", NetworkRows.securityBadge("wpa2"));
        check("wpa3 is an upper-case badge", "WPA3", NetworkRows.securityBadge("wpa3"));
        check("owe is an upper-case badge", "OWE", NetworkRows.securityBadge("OWE"));
        check("open stays lower case", "open", NetworkRows.securityBadge("open"));
        check("an unknown word falls back to auto", "auto",
                NetworkRows.securityBadge("wep"));
    }

    private static void passwordStateComesFromTheSecretIds() {
        List<NetworkRows.Row> rows = NetworkRows.rows(
                profiles(new Profile("p1", "Home", "wpa2"),
                        new Profile("p2", "Open", "open")),
                secrets("p1"), new LinkedHashMap<String, String>());

        check("a stored secret reads password saved", "password saved",
                rows.get(0).passwordLabel());
        check("an absent secret reads no password", "no password",
                rows.get(1).passwordLabel());
        check("no secret has the plain hint", "Password",
                NetworkRows.passwordHint(false));
        check("a stored secret says the field replaces it",
                "Saved - type a new password to replace",
                NetworkRows.passwordHint(true));
    }

    private static void suggestionStatusIsParsedPerProfile() {
        Map<String, String> parsed = NetworkRows.parseStatuses(
                "suggestion p1: active; suggestion p2: enter Wi-Fi password");
        check("the first status is parsed", "active", parsed.get("p1"));
        check("the second status is parsed", "enter Wi-Fi password", parsed.get("p2"));
        check("no extra statuses appear", 2, parsed.size());

        List<NetworkRows.Row> rows = NetworkRows.rows(
                profiles(new Profile("p1", "Home", "wpa2"),
                        new Profile("p2", "Lab", "wpa3")),
                secrets("p1", "p2"), parsed);
        check("the first row carries its own status", "suggestion: active",
                rows.get(0).statusLabel());
        check("the second row carries its own status",
                "suggestion: enter Wi-Fi password", rows.get(1).statusLabel());
    }

    private static void suggestionStatusWithoutAPassIsDefined() {
        Map<String, String> parsed = NetworkRows.parseStatuses("no saved network");
        check("a non-suggestion detail yields no statuses", 0, parsed.size());
        check("a local-only detail yields no statuses", 0,
                NetworkRows.parseStatuses("Home").size());
        check("a null detail yields no statuses", 0,
                NetworkRows.parseStatuses(null).size());
        check("an unknown status reads not tried yet", "suggestion: not tried yet",
                NetworkRows.statusLabel(""));
        check("a blank status reads not tried yet", "suggestion: not tried yet",
                NetworkRows.statusLabel("  "));
    }

    private static void summaryNamesTheFirstNetwork() {
        List<NetworkRows.Row> none = NetworkRows.rows(
                profiles(), secrets(), new LinkedHashMap<String, String>());
        check("no rows summarize as no network saved", "No network saved",
                NetworkRows.summary(none));
        check("a null row list is the empty state", "No network saved",
                NetworkRows.summary(null));

        List<NetworkRows.Row> one = NetworkRows.rows(
                profiles(new Profile("p1", "Flat", "owe")), secrets(),
                new LinkedHashMap<String, String>());
        check("one row names the first network", "1 network saved. Flat is tried first.",
                NetworkRows.summary(one));
    }

    private static void deleteConfirmationNamesTheSsidAndThePassword() {
        check("the confirmation names the SSID and the password", "Delete \"Home\"?"
                + " The saved password will be discarded. This cannot be undone.",
                NetworkRows.deleteConfirmation("Home"));
        check("the confirmation trims the SSID", "Delete \"Lab\"?"
                + " The saved password will be discarded. This cannot be undone.",
                NetworkRows.deleteConfirmation("  Lab  "));
        check("a blank SSID still asks about a network", "Delete this network?"
                + " The saved password will be discarded. This cannot be undone.",
                NetworkRows.deleteConfirmation(""));
        check("a null SSID still asks about a network", "Delete this network?"
                + " The saved password will be discarded. This cannot be undone.",
                NetworkRows.deleteConfirmation(null));
    }
}

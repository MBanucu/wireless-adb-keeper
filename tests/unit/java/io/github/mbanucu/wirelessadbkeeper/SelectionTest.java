package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Local-only profile selection without Android: the priority order, the scan
 * check, keeping an existing link, advancing past a failed profile, the cycle
 * bound and a stable tie.
 */
public final class SelectionTest {

    private SelectionTest() {
    }

    public static void main(String[] args) {
        highestPriorityPresentProfileWins();
        onlyTheLowerPrioritySsidPresentWins();
        nothingPresentKeepsTheCurrentLink();
        nothingPresentAndNoLinkIsANoCandidate();
        aFailedProfileAdvancesToTheNextPresentOne();
        oneCycleTriesAtMostThreeProfiles();
        aTieIsDeterministic();
        labelsNameTheProfile();
        report("SelectionTest");
    }

    private static void highestPriorityPresentProfileWins() {
        Selection.Choice choice = Selection.choose(three(), scan("Home", "Lab"), null);

        check("a present higher-priority profile is chosen", true, choice.chosen());
        check("the first present profile is the highest priority", "default",
                choice.profile().id());
        check("the chosen profile keeps its SSID", "Home", choice.profile().ssid());
        check("everything was checked for the scan",
                "Home (default), Lab (p1), Flat (p2)", choice.triedText());
    }

    private static void onlyTheLowerPrioritySsidPresentWins() {
        Selection.Choice choice = Selection.choose(three(), scan("Lab", "Flat"), null);

        check("a lower-priority present profile is chosen when the first is absent",
                "p1", choice.profile().id());
        check("the chosen SSID is the one in the scan", "Lab", choice.profile().ssid());
    }

    private static void nothingPresentKeepsTheCurrentLink() {
        Selection.Choice choice = Selection.choose(three(), scan(), "Lab");

        check("nothing present does not choose a new profile", false, choice.chosen());
        check("an existing link is kept", true, choice.kept());
        check("the kept link is attributed to its profile", "p1", choice.link().id());
        check("a kept link is not a no-candidate", false, choice.noCandidate());
        check("the status names the kept profile", "Lab (p1)",
                Selection.label(choice.link()));
    }

    private static void nothingPresentAndNoLinkIsANoCandidate() {
        Selection.Choice choice = Selection.choose(three(), scan(), null);

        check("nothing present and no link is an explicit no-candidate", true,
                choice.noCandidate());
        check("no profile is chosen", false, choice.chosen());
        check("no link is kept", false, choice.kept());
        check("the result names every profile that was tried", 3, choice.tried().size());
        check("the no-candidate text names the first profile",
                true, choice.triedText().contains("Home (default)"));
        check("the no-candidate text names the last profile",
                true, choice.triedText().contains("Flat (p2)"));
    }

    private static void aFailedProfileAdvancesToTheNextPresentOne() {
        Selection.Choice first = Selection.choose(three(), scan("Home", "Lab", "Flat"), null);
        Selection.Choice second = Selection.choose(three(), scan("Home", "Lab", "Flat"), null,
                Collections.singletonList("default"));
        Selection.Choice third = Selection.choose(three(), scan("Home", "Lab", "Flat"), null,
                Arrays.asList("default", "p1"));
        Selection.Choice done = Selection.choose(three(), scan("Home", "Lab", "Flat"), null,
                Arrays.asList("default", "p1", "p2"));

        check("the cycle starts with the highest priority", "default", first.profile().id());
        check("a failed profile advances to the next present one", "p1",
                second.profile().id());
        check("the advance records the profile already attempted", "Home (default)",
                second.attemptedText());
        check("a second failure advances again", "p2", third.profile().id());
        check("both earlier profiles are named as attempted",
                "Home (default), Lab (p1)", third.attemptedText());
        check("after every present profile the cycle stops", true, done.noCandidate());
        check("the exhausted cycle names what was attempted",
                "Home (default), Lab (p1), Flat (p2)", done.attemptedText());
    }

    private static void oneCycleTriesAtMostThreeProfiles() {
        List<Profile> many = Arrays.asList(
                new Profile("p1", "One", ""),
                new Profile("p2", "Two", ""),
                new Profile("p3", "Three", ""),
                new Profile("p4", "Four", ""),
                new Profile("p5", "Five", ""));
        List<String> scanned = scan("One", "Two", "Three", "Four", "Five");

        List<Profile> candidates = Selection.candidates(many, scanned);
        check("the cycle bound is three", 3, Selection.MAX_PROFILES_PER_CYCLE);
        check("no more than three present profiles are candidates", 3, candidates.size());
        check("the first candidate keeps the priority order", "p1", candidates.get(0).id());
        check("the last candidate is the third present profile", "p3",
                candidates.get(2).id());

        Selection.Choice stopped = Selection.choose(many, scanned, null,
                Arrays.asList("p1", "p2", "p3"));
        check("a full cycle does not fall through to the fourth profile", true,
                stopped.noCandidate());
        check("the stopped cycle names only the three attempted profiles",
                "One (p1), Two (p2), Three (p3)", stopped.attemptedText());
    }

    private static void aTieIsDeterministic() {
        List<Profile> forward = Arrays.asList(
                new Profile("p2", "Lab", ""),
                new Profile("p1", "Lab", ""));
        List<Profile> backward = Arrays.asList(
                new Profile("p1", "Lab", ""),
                new Profile("p2", "Lab", ""));

        Selection.Choice first = Selection.choose(forward, scan("Lab"), null);
        Selection.Choice second = Selection.choose(forward, scan("Lab"), null);
        Selection.Choice reversed = Selection.choose(backward, scan("Lab"), null);

        check("a duplicated SSID resolves to the smaller id", "p1", first.profile().id());
        check("repeating the same input is deterministic", "p1", second.profile().id());
        check("the same candidates in either order resolve the same way", "p1",
                reversed.profile().id());
    }

    private static void labelsNameTheProfile() {
        check("the status label is ssid then id", "lab (p2)",
                Selection.label(new Profile("p2", "lab", "")));
        check("a null profile has no label", "", Selection.label(null));
    }

    private static List<Profile> three() {
        return Arrays.asList(
                new Profile("default", "Home", "wpa2"),
                new Profile("p1", "Lab", "wpa3"),
                new Profile("p2", "Flat", "wpa2"));
    }

    private static List<String> scan(String... ssids) {
        return new ArrayList<>(Arrays.asList(ssids));
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Rotation across app updates: the previous file, its version/boot label, and
 * the page-facing marker text.
 *
 * <p>The rotation itself is pure file work, so the suite can assert the exact
 * sentence the page shows instead of trusting the activity to phrase it.
 */
public final class DiagRotationTest {

    private DiagRotationTest() {
    }

    public static void main(String[] args) throws Exception {
        check("the marker names the version, boot and event count",
                "previous build 1.9 (10), boot 42: 12 events from the previous install",
                DiagRotation.marker("1.9 (10)", "42", 12));
        check("the marker uses the singular for one event",
                "previous build 1.9 (10), boot 42: 1 event from the previous install",
                DiagRotation.marker("1.9 (10)", "42", 1));
        check("a missing version and boot are labelled unknown",
                "previous build unknown, boot unknown: 3 events from the previous install",
                DiagRotation.marker(null, "", 3));
        check("no directory has no previous marker", "", DiagRotation.previousMarker(null));

        File dir = Files.createTempDirectory("diag-rotation").toFile();
        try {
            File current = new File(dir, DiagRotation.CURRENT_FILE);
            check("the first run records the current build", true,
                    DiagRotation.maybeRotate(dir, "1.9 (10)", "42"));
            check("the first run keeps no previous build", "",
                    DiagRotation.previousMarker(dir));
            DiagJournal.write(current, lines("first", "second"));
            check("the first run leaves the journal in place", true, current.isFile());
            check("the same build does not rotate again", false,
                    DiagRotation.maybeRotate(dir, "1.9 (10)", "99"));

            check("an update rotates the journal", true,
                    DiagRotation.maybeRotate(dir, "1.10 (11)", "43"));
            check("the current file was moved away", false, current.isFile());
            File previous = new File(dir, DiagRotation.PREVIOUS_FILE);
            check("the previous file holds the old lines", 2, DiagJournal.count(previous));
            check("the previous marker names the old build",
                    "previous build 1.9 (10), boot 42: 2 events from the previous install",
                    DiagRotation.previousMarker(dir));
            checkTrue("the previous journal is readable",
                    DiagRotation.previousJournal(dir).contains("first"));
            check("the new build does not rotate again", false,
                    DiagRotation.maybeRotate(dir, "1.10 (11)", "43"));

            DiagJournal.write(current, lines("third"));
            check("a second update rotates again", true,
                    DiagRotation.maybeRotate(dir, "1.11 (12)", "44"));
            check("only the last previous file is kept", 1, DiagJournal.count(previous));
            check("the second previous marker names the middle build",
                    "previous build 1.10 (11), boot 43: 1 event from the previous install",
                    DiagRotation.previousMarker(dir));
            check("the second update keeps only one previous file",
                    true, DiagRotation.previousJournal(dir).contains("third"));

            File old = Files.createTempDirectory("diag-rotation-old").toFile();
            try {
                DiagJournal.write(new File(old, DiagRotation.CURRENT_FILE),
                        lines("pre-feature"));
                check("a pre-feature journal is rotated on first sight", true,
                        DiagRotation.maybeRotate(old, "1.9 (10)", "42"));
                check("its build is labelled unknown, not this one",
                        "previous build unknown, boot unknown: 1 event from the previous install",
                        DiagRotation.previousMarker(old));
            } finally {
                deleteTree(old);
            }
        } finally {
            deleteTree(dir);
        }

        report("DiagRotationTest");
    }

    private static List<String> lines(String... details) {
        return Arrays.asList(details).stream()
                .map(detail -> DiagFormat.line("2026-11-23T17:00:00Z", 1, "info",
                        "boot.received", detail))
                .collect(java.util.stream.Collectors.toList());
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

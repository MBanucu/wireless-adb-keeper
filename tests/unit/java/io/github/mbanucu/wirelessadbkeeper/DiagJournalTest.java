package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Journal retention and redaction, exercised through the append path.
 *
 * <p>These are the checks the Android adapter cannot make for itself: they use
 * a temporary file and the same {@link DiagJournal#append} that
 * {@link Diag#event} calls, so trimming, "last day", off and the secret canaries
 * are asserted without a device.
 */
public final class DiagJournalTest {
    private static final long NOW = 1_700_000_000_000L;

    private DiagJournalTest() {
    }

    public static void main(String[] args) throws Exception {
        check("the default mode writes", true,
                DiagRetention.writes(DiagRetention.Mode.DEFAULT));
        check("last day writes", true, DiagRetention.writes(DiagRetention.Mode.DAY));
        check("off does not write", false, DiagRetention.writes(DiagRetention.Mode.OFF));
        check("an absent stored mode means the default", DiagRetention.Mode.DEFAULT,
                DiagRetention.Mode.fromValue(null));
        check("an unknown stored mode means the default", DiagRetention.Mode.DEFAULT,
                DiagRetention.Mode.fromValue("nonsense"));
        check("the day value round-trips", DiagRetention.Mode.DAY,
                DiagRetention.Mode.fromValue(DiagRetention.Mode.DAY.value()));

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= DiagRetention.MAX_EVENTS; i++) {
            many.add(line(NOW + i * 1000L, "event " + i));
        }
        List<String> kept = DiagRetention.apply(DiagRetention.Mode.DEFAULT, many, NOW);
        check("the default keeps exactly 200 events", DiagRetention.MAX_EVENTS, kept.size());
        check("the default drops the oldest event", line(NOW + 1000L, "event 1"), kept.get(0));
        check("the default keeps the newest event",
                line(NOW + DiagRetention.MAX_EVENTS * 1000L,
                        "event " + DiagRetention.MAX_EVENTS),
                kept.get(kept.size() - 1));

        List<String> big = new ArrayList<>();
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 900; i++) filler.append('x');
        for (int i = 0; i < 100; i++) {
            big.add(line(NOW + i * 1000L, i + " " + filler));
        }
        List<String> byteKept = DiagRetention.apply(DiagRetention.Mode.DEFAULT, big, NOW);
        checkTrue("the 32 KiB cap is enforced", bytesOf(byteKept) <= DiagRetention.MAX_BYTES);
        checkTrue("the byte cap does not keep everything", byteKept.size() < big.size());
        check("the byte cap keeps the newest line", big.get(big.size() - 1),
                byteKept.get(byteKept.size() - 1));

        String boundary = line(NOW - DiagRetention.DAY_MS, "boundary");
        String recent = line(NOW - 1000L, "recent");
        String ancient = line(NOW - DiagRetention.DAY_MS - 1000L, "ancient");
        List<String> day = DiagRetention.apply(DiagRetention.Mode.DAY,
                Arrays.asList(ancient, boundary, recent), NOW);
        check("last day drops an event older than 24 hours",
                Arrays.asList(boundary, recent), day);
        check("last day keeps a line with no timestamp", 2,
                DiagRetention.apply(DiagRetention.Mode.DAY,
                        Arrays.asList("hand-edited", recent), NOW).size());
        check("off trims everything", 0,
                DiagRetention.apply(DiagRetention.Mode.OFF,
                        Arrays.asList(recent), NOW).size());

        File dir = Files.createTempDirectory("diag-journal").toFile();
        try {
            File journal = new File(dir, DiagRotation.CURRENT_FILE);
            String passphrase = "canary-passphrase-93af";
            String token = "canary-token-71c4de";
            String pairing = "canary-pairing-550e84";
            DiagJournal.append(journal, NOW, 1, "info", "probe.passphrase",
                    "wifi passphrase=" + passphrase, DiagRetention.Mode.DEFAULT);
            DiagJournal.append(journal, NOW + 1000L, 2, "warn", "probe.token",
                    "control token=" + token, DiagRetention.Mode.DEFAULT);
            DiagJournal.append(journal, NOW + 2000L, 3, "info", "probe.pairing",
                    "pairing_code=" + pairing, DiagRetention.Mode.DEFAULT);
            String text = new String(Files.readAllBytes(journal.toPath()),
                    StandardCharsets.UTF_8);
            check("the canary journal has three entries", 3, DiagJournal.count(journal));
            checkTrue("the passphrase canary is not in the file", !text.contains(passphrase));
            checkTrue("the token canary is not in the file", !text.contains(token));
            checkTrue("the pairing canary is not in the file", !text.contains(pairing));
            checkTrue("the passphrase is marked redacted", text.contains("passphrase=***"));
            checkTrue("the token is marked redacted", text.contains("token=***"));
            checkTrue("the pairing code is marked redacted", text.contains("pairing_code=***"));

            File off = new File(dir, "off.log");
            String written = DiagJournal.append(off, NOW, 1, "info", "probe.off",
                    "passphrase=" + passphrase, DiagRetention.Mode.OFF);
            check("off returns no line", null, written);
            check("off does not create the file", false, off.exists());

            File existing = new File(dir, "existing.log");
            DiagJournal.append(existing, NOW, 1, "info", "probe.before", "kept",
                    DiagRetention.Mode.DEFAULT);
            byte[] before = Files.readAllBytes(existing.toPath());
            DiagJournal.append(existing, NOW + 1000L, 2, "info", "probe.off",
                    "passphrase=" + passphrase, DiagRetention.Mode.OFF);
            checkTrue("off leaves an existing file byte-identical",
                    Arrays.equals(before, Files.readAllBytes(existing.toPath())));

            File ring = new File(dir, "ring.log");
            for (int i = 0; i <= DiagRetention.MAX_EVENTS; i++) {
                DiagJournal.append(ring, NOW + i * 1000L, i, "info", "probe.ring",
                        "event " + i, DiagRetention.Mode.DEFAULT);
            }
            check("appending trims at 200 events", DiagRetention.MAX_EVENTS,
                    DiagJournal.count(ring));
            checkTrue("appending keeps the newest event",
                    DiagJournal.journal(ring).contains("event " + DiagRetention.MAX_EVENTS));
            checkTrue("appending drops the oldest event",
                    !DiagJournal.journal(ring).contains("event 0 "));
        } finally {
            deleteTree(dir);
        }

        report("DiagJournalTest");
    }

    private static String line(long ms, String detail) {
        return DiagFormat.line(DiagRetention.time(ms), 1, "info", "probe.ring", detail);
    }

    private static long bytesOf(List<String> lines) {
        long bytes = 0;
        for (String line : lines) bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        return bytes;
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

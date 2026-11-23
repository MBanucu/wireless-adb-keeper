package io.github.mbanucu.wirelessadbkeeper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Host-side redaction probe: writes fake secrets through the real journal
 * append path and asserts the file contains none of them.
 *
 * <p>{@link Diag#event} resolves Android storage and then calls
 * {@link DiagJournal#append}; this probe calls the same append, formatting and
 * scrubbing code against a temporary file, so it needs no device. A secret that
 * reaches the file makes the probe exit non-zero instead of printing a warning,
 * because a diagnostics journal is exactly the text a user pastes in public.
 */
public final class DiagProbe {
    // Distinct enough that a substring cannot appear by accident.
    private static final String PASSPHRASE = "canary-passphrase-93af";
    private static final String TOKEN = "canary-token-71c4de";
    private static final String PAIRING_CODE = "canary-pairing-550e84";

    private DiagProbe() {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/tmp/diag-redaction-probe");
        deleteTree(dir);
        if (!dir.mkdirs()) throw new IllegalStateException("could not create " + dir);
        File file = new File(dir, DiagRotation.CURRENT_FILE);
        long now = 1_700_000_000_000L;
        int failures = 0;

        String first = DiagJournal.append(file, now, 1, "info", "probe.passphrase",
                "wifi passphrase=" + PASSPHRASE, DiagRetention.Mode.DEFAULT);
        String second = DiagJournal.append(file, now + 1000L, 2, "warn", "probe.token",
                "control token=" + TOKEN, DiagRetention.Mode.DEFAULT);
        String third = DiagJournal.append(file, now + 2000L, 3, "info", "probe.pairing",
                "pairing_code=" + PAIRING_CODE, DiagRetention.Mode.DEFAULT);

        failures += check("three entries were appended",
                file.isFile() && DiagJournal.count(file) == 3);
        failures += check("the append returns the scrubbed line",
                first != null && first.contains("passphrase=***"));

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        failures += check("the passphrase canary is absent", !content.contains(PASSPHRASE));
        failures += check("the token canary is absent", !content.contains(TOKEN));
        failures += check("the pairing-code canary is absent", !content.contains(PAIRING_CODE));
        failures += check("the passphrase marker is present", content.contains("passphrase=***"));
        failures += check("the token marker is present", content.contains("token=***"));
        failures += check("the pairing marker is present", content.contains("pairing_code=***"));

        String[] lines = content.split("\n", -1);
        int nonEmpty = 0;
        boolean formatOk = true;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            nonEmpty++;
            if (!line.matches(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z \\d+s "
                            + "(info|warn|error) [a-z0-9_.]+( .*)?")) {
                formatOk = false;
                System.out.println("FAIL unexpected line shape: " + line);
            }
        }
        failures += check("every line keeps the journal shape", nonEmpty == 3 && formatOk);
        failures += check("the three returned lines are not null",
                second != null && third != null);

        File off = new File(dir, "off-exists.log");
        DiagJournal.append(off, now, 1, "info", "probe.off",
                "passphrase=" + PASSPHRASE, DiagRetention.Mode.OFF);
        failures += check("retention off creates no file", !off.exists());

        File existing = new File(dir, "off-existing.log");
        DiagJournal.append(existing, now, 1, "info", "probe.before", "kept",
                DiagRetention.Mode.DEFAULT);
        byte[] before = Files.readAllBytes(existing.toPath());
        DiagJournal.append(existing, now + 1000L, 2, "info", "probe.off",
                "passphrase=" + PASSPHRASE, DiagRetention.Mode.OFF);
        failures += check("retention off leaves an existing file unchanged",
                java.util.Arrays.equals(before, Files.readAllBytes(existing.toPath())));

        deleteTree(dir);
        if (failures == 0) {
            System.out.println("redaction probe: PASS");
        } else {
            System.out.println("redaction probe: FAIL " + failures + " check(s)");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
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

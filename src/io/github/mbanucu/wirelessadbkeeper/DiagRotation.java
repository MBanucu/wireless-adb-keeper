package io.github.mbanucu.wirelessadbkeeper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Keeps the journal from the previous install and labels it, without Android.
 *
 * <p>An update must not let yesterday's lines look like this build's. On the
 * first append after a version change the current {@code events.log} is moved to
 * {@code events.previous.log}; the metadata beside both files records the
 * version and the boot id so the page can say which build the older block came
 * from. Only the last file is kept, as the issue asks: a second update replaces
 * it rather than growing a history.
 *
 * <p>An install that predates this class has a journal but no metadata. That
 * file is treated as coming from an unknown previous build, which is honest:
 * claiming it was written by this version would be the exact mistake this class
 * exists to avoid.
 */
public final class DiagRotation {
    /** The current journal; {@link Diag#FILE} must stay equal to this. */
    public static final String CURRENT_FILE = "events.log";
    /** The one kept file from before the last version change. */
    public static final String PREVIOUS_FILE = "events.previous.log";
    /** Version and boot the current journal started in. */
    static final String CURRENT_META = "current.meta";
    /** Version and boot the kept previous journal ended in. */
    static final String PREVIOUS_META = "previous.meta";
    /** What a file that predates the metadata is labelled with. */
    public static final String UNKNOWN = "unknown";

    private DiagRotation() {
    }

    /**
     * Rotates once per version change. Returns true when it moved or wrote
     * metadata; a read-only or broken directory returns false and must not stop
     * the journal entry.
     */
    public static boolean maybeRotate(File dir, String version, String bootId) {
        if (dir == null || version == null || version.isEmpty()) return false;
        String boot = clean(bootId);
        try {
            File current = new File(dir, CURRENT_FILE);
            Meta installed = readMeta(new File(dir, CURRENT_META));
            if (installed == null) {
                // First run of this feature. An existing journal came from a
                // build that did not record its version, so say so.
                if (DiagJournal.count(current) > 0) {
                    keepPrevious(dir, current, new Meta(UNKNOWN, UNKNOWN));
                }
                writeMeta(new File(dir, CURRENT_META), new Meta(version, boot));
                return true;
            }
            if (version.equals(installed.version)) return false;
            if (DiagJournal.count(current) > 0) {
                keepPrevious(dir, current, installed);
            }
            writeMeta(new File(dir, CURRENT_META), new Meta(version, boot));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The human label for the kept file, or "" when there is nothing to show. */
    public static String previousMarker(File dir) {
        if (dir == null) return "";
        Meta meta = readMeta(new File(dir, PREVIOUS_META));
        if (meta == null) return "";
        int events = DiagJournal.count(new File(dir, PREVIOUS_FILE));
        if (events == 0) return "";
        return marker(meta.version, meta.boot, events);
    }

    /** The kept journal text, oldest first; "" when there is none. */
    public static String previousJournal(File dir) {
        if (dir == null) return "";
        return DiagJournal.journal(new File(dir, PREVIOUS_FILE));
    }

    /** The exact sentence the page and the report use for the older block. */
    public static String marker(String version, String bootId, int events) {
        String count = events == 1 ? "event" : "events";
        return "previous build " + clean(version) + ", boot " + clean(bootId) + ": "
                + events + " " + count + " from the previous install";
    }

    private static void keepPrevious(File dir, File current, Meta meta) throws IOException {
        File previous = new File(dir, PREVIOUS_FILE);
        Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
        writeMeta(new File(dir, PREVIOUS_META), meta);
    }

    private static Meta readMeta(File file) {
        if (file == null || !file.isFile()) return null;
        String version = null;
        String boot = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int split = line.indexOf('=');
                if (split < 0) continue;
                String key = line.substring(0, split);
                String value = line.substring(split + 1);
                if ("version".equals(key)) version = value;
                if ("boot".equals(key)) boot = value;
            }
        } catch (Exception ignored) {
            return null;
        }
        if (version == null || version.isEmpty()) return null;
        return new Meta(version, clean(boot));
    }

    private static void writeMeta(File file, Meta meta) throws IOException {
        try (OutputStreamWriter out = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write("version=" + meta.version + "\n");
            out.write("boot=" + meta.boot + "\n");
        }
    }

    private static String clean(String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }

    private static final class Meta {
        final String version;
        final String boot;

        Meta(String version, String boot) {
            this.version = version;
            this.boot = boot;
        }
    }
}

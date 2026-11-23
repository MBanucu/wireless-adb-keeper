package io.github.mbanucu.wirelessadbkeeper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal file's read, trim and write path, without Android.
 *
 * <p>{@link Diag#event} resolves the file and the retention mode, then delegates
 * here, so the redaction probe can exercise the production append path against a
 * temporary file with no device. Formatting and scrubbing stay in
 * {@link DiagFormat}; the mode decision stays in {@link DiagRetention}.
 *
 * <p>Off is checked before the file is opened: the caller gets {@code null} and
 * the journal is not created, emptied or touched. Every other mode trims on
 * append, newest last.
 */
public final class DiagJournal {

    private DiagJournal() {
    }

    /**
     * Appends one entry and returns the line that was written, or null when the
     * retention mode is off.
     */
    public static String append(File file, long nowMs, long uptimeSeconds, String level,
                                String code, String detail, DiagRetention.Mode mode)
            throws IOException {
        if (file == null || code == null || !DiagRetention.writes(mode)) return null;
        String line = DiagFormat.line(DiagRetention.time(nowMs), uptimeSeconds, level, code,
                detail);
        List<String> lines = read(file);
        lines.add(line);
        write(file, DiagRetention.apply(mode, lines, nowMs));
        return line;
    }

    /** The journal, oldest first, one entry per line. Empty when nothing ran yet. */
    public static String journal(File file) {
        List<String> lines = read(file);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    /** How many entries the file holds. */
    public static int count(File file) {
        return read(file).size();
    }

    /** Reads non-empty lines; a missing file is an empty journal, not an error. */
    public static List<String> read(File file) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.isFile()) return lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (Exception ignored) {
            // The caller (Diag) is the diagnostics path: a broken journal must
            // never take the service down. An unreadable file reads as empty.
        }
        return lines;
    }

    /** Replaces the file with {@code lines}, one per line; used by the rotation. */
    public static void write(File file, List<String> lines) throws IOException {
        try (OutputStreamWriter out = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        }
    }
}

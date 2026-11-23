package io.github.mbanucu.wirelessadbkeeper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal's text rules, separated from the file it is written to.
 *
 * <p>Every entry is {@code time uptime level code detail}; the detail is
 * scrubbed of the three secret shapes before it can reach the journal or
 * logcat. The ring keeps the newest entries: both caps drop from the front, and
 * a single line larger than the byte cap is kept rather than emptying the
 * journal exactly when it is needed.
 */
public final class DiagFormat {

    private DiagFormat() {
    }

    /** One line: {@code time uptime}s level code [scrubbed detail]. */
    public static String line(String time, long uptimeSeconds, String level, String code,
                              String detail) {
        String out = time + " " + uptimeSeconds + "s " + level + " " + code;
        if (detail != null && !detail.isEmpty()) {
            out += " " + scrub(detail);
        }
        return out;
    }

    /** Removes the one secret shape that is easy to include by accident. */
    public static String scrub(String detail) {
        String out = detail.replaceAll("(?i)passphrase=\\S+", "passphrase=***");
        out = out.replaceAll("(?i)token=\\S+", "token=***");
        out = out.replaceAll("(?i)pairing_code=\\S+", "pairing_code=***");
        return out.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Enforces both caps from the front and returns the surviving lines.
     *
     * <p>The input is not modified. The newest line is always kept: when one
     * line alone exceeds {@code maxBytes}, dropping it would leave a journal
     * that cannot explain the event that filled it.
     */
    public static List<String> trim(List<String> lines, int maxLines, long maxBytes) {
        List<String> kept = new ArrayList<>(lines);
        while (kept.size() > maxLines) {
            kept.remove(0);
        }
        long bytes = 0;
        for (String line : kept) {
            bytes += bytesOf(line);
        }
        while (bytes > maxBytes && kept.size() > 1) {
            bytes -= bytesOf(kept.remove(0));
        }
        return kept;
    }

    private static long bytesOf(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }
}

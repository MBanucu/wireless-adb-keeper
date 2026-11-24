package io.github.mbanucu.wirelessadbkeeper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * How much of the journal to keep, without Android.
 *
 * <p>The default is the historic ring: the newest 200 events and at most 32 KiB.
 * "Last day" drops entries older than 24 hours, still bounded by the byte cap so
 * a verbose window cannot make the file grow without limit. "Off" is not a
 * trim: {@link #writes} returns false and the caller must not touch the file at
 * all, so the journal stops growing instead of being emptied.
 *
 * <p>The mode is stored in device-protected prefs by
 * {@link DiagRetentionStore}; the page and the service read it on every append,
 * so a change reaches a running monitor without a restart.
 */
public final class DiagRetention {
    /** Events kept by the default mode. */
    public static final int MAX_EVENTS = 200;
    /** Hard byte cap: whichever limit is reached first wins. */
    public static final long MAX_BYTES = 32L * 1024L;
    /** The "last day" window. */
    public static final long DAY_MS = 24L * 60L * 60L * 1000L;

    static final String TIME = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    /** One of the three documented journal lengths. */
    public enum Mode {
        DEFAULT("default", "Last 200 events or 32 KiB (default)"),
        DAY("day", "Last day"),
        OFF("off", "Off: stop writing the journal");

        private final String value;
        private final String label;

        Mode(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /** The value stored in prefs. */
        public String value() {
            return value;
        }

        /** The sentence the page shows. */
        public String label() {
            return label;
        }

        /** Parses a stored value; absent or unknown means the default. */
        public static Mode fromValue(String stored) {
            if (stored != null) {
                for (Mode mode : values()) {
                    if (mode.value.equals(stored)) return mode;
                }
            }
            return DEFAULT;
        }
    }

    private DiagRetention() {
    }

    /** Whether this mode may write to the journal file at all. */
    public static boolean writes(Mode mode) {
        return mode != null && mode != Mode.OFF;
    }

    /**
     * Applies {@code mode} to the lines about to be written.
     *
     * <p>The input is not modified. {@link Mode#OFF} returns an empty list, but
     * the writer must check {@link #writes} first: returning empty still means
     * "write an empty file", which is exactly what off must not do.
     */
    public static List<String> apply(Mode mode, List<String> lines, long nowMs) {
        Mode actual = mode == null ? Mode.DEFAULT : mode;
        if (!writes(actual)) return new ArrayList<>();
        List<String> kept = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        if (actual == Mode.DAY) {
            kept = after(kept, nowMs - DAY_MS);
            return DiagFormat.trim(kept, Integer.MAX_VALUE, MAX_BYTES);
        }
        return DiagFormat.trim(kept, MAX_EVENTS, MAX_BYTES);
    }

    /** The journal timestamp for {@code ms}, in the same format as every line. */
    static String time(long ms) {
        SimpleDateFormat format = new SimpleDateFormat(TIME, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(ms));
    }

    private static List<String> after(List<String> lines, long cutoffMs) {
        String cutoff = time(cutoffMs);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String stamp = stamp(line);
            // A hand-edited or foreign line has no timestamp to compare; keep it
            // rather than silently throwing away the one line that might explain
            // why the file is malformed.
            if (stamp == null || stamp.compareTo(cutoff) >= 0) kept.add(line);
        }
        return kept;
    }

    private static String stamp(String line) {
        if (line == null || line.length() < 20) return null;
        String stamp = line.substring(0, 20);
        if (stamp.charAt(4) != '-' || stamp.charAt(7) != '-' || stamp.charAt(10) != 'T'
                || stamp.charAt(13) != ':' || stamp.charAt(16) != ':'
                || stamp.charAt(19) != 'Z') {
            return null;
        }
        for (int i = 0; i < stamp.length(); i++) {
            char c = stamp.charAt(i);
            boolean separator = i == 4 || i == 7 || i == 10 || i == 13 || i == 16 || i == 19;
            if (!separator && (c < '0' || c > '9')) return null;
        }
        return stamp;
    }
}

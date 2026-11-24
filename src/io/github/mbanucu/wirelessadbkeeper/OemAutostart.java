package io.github.mbanucu.wirelessadbkeeper;

/**
 * Whether DokeOS let the app start at boot, decided without Android.
 *
 * <p>The vendor autostart list lives in System Manager behind a signature
 * permission ({@code com.freeme.freemanager.permission.ACCESS_INTERFACE}), so
 * the app cannot read it and must never guess {@code allowed} from its absence.
 * What it can observe is the effect: {@code BootReceiver} journals
 * {@code boot.received ... before unlock} when the ROM delivered the broadcast
 * at boot, and {@code ... after unlock} when the delivery waited for a manual
 * launch. The process start time says whether that was at boot or hundreds of
 * seconds later, which is exactly the measured DokeOS signature.
 *
 * <p>{@link #classify} therefore reports {@code unknown} rather than accusing
 * the vendor when the evidence is weak (no boot event in this boot's journal,
 * an early unlock, or a late process start with no matching broadcast). The
 * install script and the documentation name the screen; only the observation
 * decides the state.
 */
public final class OemAutostart {

    /** The vendor let the package start at boot. */
    public static final String ALLOWED = "allowed";
    /** The boot broadcast was withheld until the app was opened by hand. */
    public static final String BLOCKED = "blocked";
    /** The evidence available is not enough to say either way. */
    public static final String UNKNOWN = "unknown";

    /**
     * A process that starts this long after boot is not a boot start.
     *
     * <p>The allowlisted XPLORE 2 starts the monitor at ~27 s; the blocked boot
     * started it at 238 s, when the app was opened. 120 s leaves room for a slow
     * boot while still keeping the two apart. Below it, an after-unlock event is
     * reported as unknown, not blocked: an early unlock alone is not evidence.
     */
    public static final long LATE_PROCESS_START_S = 120L;

    /** The screen and the app to enable, in one sentence the fix can repeat. */
    public static final String SCREEN =
            "System Manager > App Management > Auto Start Management";

    private OemAutostart() {
    }

    /** Everything the decision is allowed to use, gathered once from the device. */
    public static final class Inputs {
        private final boolean vendor;
        private final String journal;
        private final long nowMs;
        private final long uptimeS;
        private final long processStartedAfterBootS;

        /**
         * @param vendor                  a DokeOS autostart manager was detected
         * @param journal                 the current diagnostics journal
         * @param nowMs                   wall clock, to tell this boot's lines apart
         * @param uptimeS                 {@code elapsedRealtime} now, in seconds
         * @param processStartedAfterBootS when this process started, in seconds
         */
        public Inputs(boolean vendor, String journal, long nowMs, long uptimeS,
                      long processStartedAfterBootS) {
            this.vendor = vendor;
            this.journal = journal;
            this.nowMs = nowMs;
            this.uptimeS = uptimeS;
            this.processStartedAfterBootS = processStartedAfterBootS;
        }
    }

    /** The state and the one sentence that justifies it. */
    public static final class Result {
        private final String state;
        private final String reason;

        Result(String state, String reason) {
            this.state = state;
            this.reason = reason;
        }

        public String state() {
            return state;
        }

        public String reason() {
            return reason;
        }

        /** The snapshot value: {@code state (reason)} on one line. */
        public String line() {
            return state + " (" + reason + ")";
        }
    }

    /** Classifies the evidence. Never throws; absent evidence is {@code unknown}. */
    public static Result classify(Inputs in) {
        if (in == null || !in.vendor) {
            return new Result(UNKNOWN, "no DokeOS autostart manager detected");
        }
        Boot first = firstBootEvent(in.journal, in.nowMs, in.uptimeS);
        if (first == null) {
            return new Result(UNKNOWN, "no boot.received in this boot's journal;"
                    + " check " + SCREEN + " after the next reboot");
        }
        if (first.beforeUnlock) {
            return new Result(ALLOWED, "boot.received before unlock at "
                    + first.uptimeS + " s");
        }
        if (in.processStartedAfterBootS >= LATE_PROCESS_START_S) {
            return new Result(BLOCKED, "boot.received only after unlock at "
                    + first.uptimeS + " s, process started at "
                    + in.processStartedAfterBootS + " s; fix: " + SCREEN
                    + ", enable this app");
        }
        return new Result(UNKNOWN, "boot.received after unlock at " + first.uptimeS
                + " s, process started at " + in.processStartedAfterBootS
                + " s; the vendor list is not readable, so this is not proof either way");
    }

    /**
     * The first boot event of this boot, or null.
     *
     * <p>A journal can outlive a reboot, so wall clock matters: the current
     * boot began at {@code now - uptime}, and a line before that is from an
     * earlier boot no matter what its uptime says. The first such line is the
     * one that records whether the ROM delivered the broadcast at boot.
     */
    private static Boot firstBootEvent(String journal, long nowMs, long uptimeS) {
        if (journal == null || journal.isEmpty()) return null;
        long bootStartMs = nowMs - uptimeS * 1000L;
        String bootStart = DiagRetention.time(bootStartMs);
        for (String line : journal.split("\n")) {
            // time uptime level code detail
            String[] parts = line.split(" ", 5);
            if (parts.length < 4 || !"boot.received".equals(parts[3])) continue;
            String stamp = parts[0];
            if (stamp.length() < 20 || stamp.compareTo(bootStart) < 0) continue;
            long uptime = uptimeOf(parts[1]);
            if (uptime < 0 || uptime > uptimeS) continue;
            String detail = parts.length > 4 ? parts[4] : "";
            boolean before = detail.contains("before unlock");
            boolean after = detail.contains("after unlock");
            if (!before && !after) continue;
            return new Boot(uptime, before);
        }
        return null;
    }

    /** Parses the journal's {@code 238s} uptime column; -1 when malformed. */
    private static long uptimeOf(String token) {
        if (token == null || !token.endsWith("s")) return -1L;
        try {
            return Long.parseLong(token.substring(0, token.length() - 1));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static final class Boot {
        final long uptimeS;
        final boolean beforeUnlock;

        Boot(long uptimeS, boolean beforeUnlock) {
            this.uptimeS = uptimeS;
            this.beforeUnlock = beforeUnlock;
        }
    }
}

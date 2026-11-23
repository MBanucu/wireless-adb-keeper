package io.github.mbanucu.wirelessadbkeeper;

/**
 * The opt-in verbose window: at most one hour or 200 events, whichever comes
 * first, then it switches itself off and says so once.
 *
 * <p>Everything here is Android-free. The toggle and the window counters live
 * behind {@link Store}, which the app implements over device-protected shared
 * preferences; a JVM suite can use a fake store instead. The store is read on
 * every {@link #claim}, not cached, so flipping the toggle reaches a service
 * that is already running and a reboot resumes the same window.
 *
 * <p>The high-frequency paths call {@link Diag#verbose} with one of the codes
 * below. They are separate from the coded failure events on purpose: with the
 * window off, none of them may be written.
 */
public final class VerboseLog {
    /** One hour, measured on the wall clock so it survives a process restart. */
    public static final long WINDOW_MS = 60L * 60L * 1000L;
    /** The event cap; the expiry notice is written after this many entries. */
    public static final int MAX_EVENTS = 200;

    /** The one event written when the window ends by itself. */
    public static final String EXPIRED = "diag.verbose.expired";
    /** One per port probe: port state, Termux state and what the probe did. */
    public static final String PIN = "diag.verbose.pin";
    /** One per observed settings change. */
    public static final String OBSERVER = "diag.verbose.observer";
    /** One per network callback transition. */
    public static final String NETWORK = "diag.verbose.network";
    /** One per notification update. */
    public static final String NOTIFICATION = "diag.verbose.notification";

    /** Where the toggle and the window counters are persisted. */
    public interface Store {
        boolean enabled();

        /** Wall-clock start of the window; 0 when disabled. */
        long startedAtMs();

        /** Verbose events written in this window. */
        int count();

        void save(boolean enabled, long startedAtMs, int count);
    }

    /** Where the one expiry notice goes; {@link Diag} is the app's real sink. */
    @FunctionalInterface
    public interface Sink {
        void event(String level, String code, String detail);
    }

    /** What one high-frequency call should do. */
    public enum Decision {
        /** The window is off: write nothing. */
        OFF,
        /** The window is open: write the caller's event. */
        ALLOWED,
        /** This call ended the window: the expiry notice was written, write no event. */
        EXPIRED
    }

    private VerboseLog() {
    }

    public static boolean isEnabled(Store store) {
        return store != null && store.enabled();
    }

    /** Opens a fresh window; the previous counters are dropped. */
    public static synchronized void enable(Store store, long nowMs) {
        if (store != null) store.save(true, nowMs, 0);
    }

    /** Closes the window without journalling; the caller may already have. */
    public static synchronized void disable(Store store) {
        if (store != null) store.save(false, 0L, 0);
    }

    /**
     * Writes one info event when the window allows it, and nothing when it does
     * not. This is the path the high-frequency callers take: off is silent, the
     * limit writes the single expiry notice instead of the tick, and the next
     * call is silent again.
     */
    public static void emit(Store store, long nowMs, String code, String detail, Sink sink) {
        if (sink == null) return;
        if (claim(store, nowMs, sink) == Decision.ALLOWED) {
            sink.event("info", code, detail);
        }
    }

    /**
     * Claims one verbose event.
     *
     * <p>Off is a no-op. At the time or event limit the window is saved as off
     * and exactly one {@link #EXPIRED} event goes to {@code sink}; a later call
     * then sees off and stays silent. Synchronized so two probes cannot both
     * expire the same window.
     */
    public static synchronized Decision claim(Store store, long nowMs, Sink sink) {
        if (!isEnabled(store)) return Decision.OFF;
        int count = store.count();
        long started = store.startedAtMs();
        if (count >= MAX_EVENTS) {
            return expire(store, sink, "reached " + MAX_EVENTS + " events");
        }
        if (nowMs - started >= WINDOW_MS) {
            return expire(store, sink, "one-hour window ended");
        }
        store.save(true, started, count + 1);
        return Decision.ALLOWED;
    }

    /** The snapshot line: {@code off}, or what is left of the window. */
    public static String status(Store store, long nowMs) {
        if (!isEnabled(store)) return "off";
        int events = Math.max(0, MAX_EVENTS - store.count());
        long elapsed = Math.max(0L, nowMs - store.startedAtMs());
        long minutes = Math.max(0L, WINDOW_MS - elapsed) / 60_000L;
        return "on; " + minutes + " min and " + events + " events left";
    }

    private static Decision expire(Store store, Sink sink, String why) {
        store.save(false, 0L, 0);
        if (sink != null) {
            sink.event("info", EXPIRED, "verbose logging off: " + why);
        }
        return Decision.EXPIRED;
    }
}

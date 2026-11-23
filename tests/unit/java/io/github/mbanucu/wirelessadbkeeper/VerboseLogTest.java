package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * The opt-in verbose window, tested without a device.
 *
 * <p>The fake store stands in for device-protected prefs, so these checks are
 * the contract the Android adapter has to meet: off means no event at all, on
 * means exactly one per tick, and the window ends at one hour or 200 events with
 * exactly one {@code diag.verbose.expired} entry instead of the next tick. The
 * decision deliberately reads the store on every call rather than caching it,
 * which is what lets the toggle reach a running service.
 */
public final class VerboseLogTest {

    private static final long T0 = 1_700_000_000_000L;

    private VerboseLogTest() {
    }

    public static void main(String[] args) {
        List<String> codes = Arrays.asList(VerboseLog.EXPIRED, VerboseLog.PIN,
                VerboseLog.OBSERVER, VerboseLog.NETWORK, VerboseLog.NOTIFICATION);
        for (String code : codes) {
            checkTrue("a verbose code is a stable diag.verbose code: " + code,
                    code.matches("diag\\.verbose\\.[a-z_]+"));
        }
        check("every verbose code is distinct", codes.size(), new HashSet<>(codes).size());

        FakeStore off = new FakeStore();
        List<String> offEvents = new ArrayList<>();
        VerboseLog.emit(off, T0, VerboseLog.PIN, "tick", sink(offEvents));
        check("verbose off writes no event", 0, offEvents.size());
        check("verbose off writes nothing to the store", 0, off.saves);
        check("the toggle defaults off", false, VerboseLog.isEnabled(off));
        check("the snapshot says off", "off", VerboseLog.status(off, T0));

        FakeStore on = new FakeStore();
        VerboseLog.enable(on, T0);
        check("enabling starts the window", true, VerboseLog.isEnabled(on));
        List<String> onEvents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VerboseLog.emit(on, T0 + i * 1000L, VerboseLog.PIN, "tick " + i, sink(onEvents));
        }
        check("verbose on writes one event per tick", 3, onEvents.size());
        check("the first tick carries its code and detail",
                "info diag.verbose.pin tick 0", onEvents.get(0));
        check("three ticks are three stored events", 3, on.count());
        check("the window start is kept", T0, on.startedAtMs());
        check("the snapshot counts down", "on; 60 min and 197 events left",
                VerboseLog.status(on, T0));

        // The store is the source of truth, not a cached copy: another component
        // (or the UI) can close the window and the next tick must see it.
        on.enabled = false;
        VerboseLog.emit(on, T0 + 4000L, VerboseLog.PIN, "closed elsewhere", sink(onEvents));
        check("a window closed in storage writes nothing", 3, onEvents.size());
        on.enabled = true;
        VerboseLog.emit(on, T0 + 4000L, VerboseLog.PIN, "tick 4", sink(onEvents));
        check("a window reopened in storage is seen", 4, onEvents.size());
        VerboseLog.disable(on);
        check("an explicit off closes the window", false, VerboseLog.isEnabled(on));

        FakeStore full = new FakeStore();
        VerboseLog.enable(full, T0);
        List<String> fullEvents = new ArrayList<>();
        for (int i = 0; i < VerboseLog.MAX_EVENTS; i++) {
            VerboseLog.emit(full, T0 + i, VerboseLog.PIN, "tick " + i, sink(fullEvents));
        }
        check("the event limit allows exactly 200 events", VerboseLog.MAX_EVENTS,
                fullEvents.size());
        check("the 200th event keeps the window open", true, VerboseLog.isEnabled(full));
        VerboseLog.emit(full, T0 + 300L, VerboseLog.PIN, "tick 200", sink(fullEvents));
        check("the next tick writes the expiry instead of itself",
                VerboseLog.MAX_EVENTS + 1, fullEvents.size());
        check("one event at the event limit is the expiry", 1,
                countCode(fullEvents, VerboseLog.EXPIRED));
        checkTrue("the event-limit expiry names the limit",
                fullEvents.get(VerboseLog.MAX_EVENTS).contains(VerboseLog.MAX_EVENTS + " events"));
        check("expiry switches verbose off in storage", false, VerboseLog.isEnabled(full));
        VerboseLog.emit(full, T0 + 400L, VerboseLog.PIN, "tick 201", sink(fullEvents));
        check("a later tick is silent", VerboseLog.MAX_EVENTS + 1, fullEvents.size());
        check("the event-limit expiry stays exactly one event", 1,
                countCode(fullEvents, VerboseLog.EXPIRED));

        FakeStore timed = new FakeStore();
        VerboseLog.enable(timed, T0);
        List<String> timeEvents = new ArrayList<>();
        VerboseLog.emit(timed, T0 + VerboseLog.WINDOW_MS - 1, VerboseLog.PIN, "inside",
                sink(timeEvents));
        check("the last millisecond of the hour is inside the window",
                "info diag.verbose.pin inside", timeEvents.get(0));
        VerboseLog.emit(timed, T0 + VerboseLog.WINDOW_MS, VerboseLog.PIN, "outside",
                sink(timeEvents));
        check("the hour boundary replaces the tick with the expiry", 2, timeEvents.size());
        check("one event at the time limit is the expiry", 1,
                countCode(timeEvents, VerboseLog.EXPIRED));
        checkTrue("the time-limit expiry names the hour",
                timeEvents.get(1).contains("one-hour"));
        check("the window is off after the hour", false, VerboseLog.isEnabled(timed));
        VerboseLog.emit(timed, T0 + VerboseLog.WINDOW_MS + 1, VerboseLog.PIN, "after",
                sink(timeEvents));
        check("a later tick after the hour is silent", 2, timeEvents.size());
        check("the time-limit expiry stays exactly one event", 1,
                countCode(timeEvents, VerboseLog.EXPIRED));

        VerboseLog.enable(timed, T0 + VerboseLog.WINDOW_MS + 1000L);
        check("enabling again starts a fresh window", "on; 60 min and 200 events left",
                VerboseLog.status(timed, T0 + VerboseLog.WINDOW_MS + 1000L));
        check("enabling again resets the counter", 0, timed.count());

        report("VerboseLogTest");
    }

    private static int countCode(List<String> events, String code) {
        int count = 0;
        for (String event : events) {
            if (event.contains(" " + code + " ") || event.endsWith(" " + code)) count++;
        }
        return count;
    }

    private static VerboseLog.Sink sink(List<String> events) {
        return (level, code, detail) -> events.add(level + " " + code + " " + detail);
    }

    /** Stands in for the device-protected prefs the app stores the window in. */
    private static final class FakeStore implements VerboseLog.Store {
        boolean enabled;
        long startedAt;
        int count;
        int saves;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public long startedAtMs() {
            return startedAt;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public void save(boolean enabled, long startedAtMs, int count) {
            this.enabled = enabled;
            this.startedAt = startedAtMs;
            this.count = count;
            saves++;
        }
    }
}

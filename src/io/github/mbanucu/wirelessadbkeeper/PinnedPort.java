package io.github.mbanucu.wirelessadbkeeper;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps adbd listening on a fixed TCP port, so `adb connect <phone>:5555` keeps
 * working across adbd restarts.
 *
 * The app cannot do this by itself. `adb tcpip 5555` is an adb service request,
 * so it needs both an adb client and a key adbd accepts; an app has neither, and
 * the property it sets (`service.adb.tcp.port`) is refused to `setprop` from the
 * shell user. Termux on the same phone has an adb client whose key is already
 * paired, so the app's part is to notice that the port is gone and ask Termux to
 * run the script that sends `tcpip:5555`. Every decision — when to ask, how
 * often, whether Termux can be used at all — stays here.
 */
public final class PinnedPort {
    public static final int PORT = 5555;

    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_PERM = "com.termux.permission.RUN_COMMAND";
    private static final String RUN_ACTION = "com.termux.RUN_COMMAND";
    private static final String RUN_SERVICE = "com.termux.app.RunCommandService";
    /**
     * Termux &ge; 0.109 sends the result to this PendingIntent. The extra name
     * and the bundle keys inside the result are from TermuxConstants; see
     * {@link TermuxResultService} and the RUN_COMMAND wiki page.
     */
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    /** Copied there by the checkout's install step; see README. */
    private static final String SCRIPT = TERMUX_HOME + "/wireless-adb-keeper/pin-adb-port.sh";

    /** Asked again only after this long, so a stuck adbd is not hammered. */
    private static final long MIN_INTERVAL_MS = 30_000L;
    /**
     * How long a script's own RESULT line is trusted over the probe. The probe
     * remains the authority on whether the socket answers; this only keeps a
     * reason or a just-reported port on screen instead of dropping it.
     */
    private static final long RESULT_FRESH_MS = 5 * 60_000L;
    /** No result this long after an ask means the probe is the fallback. */
    private static final long FALLBACK_MS = 60_000L;

    /**
     * Guards {@link #lastAsk} and {@link #state}. Never held across a socket
     * probe or a Termux intent: the lock only claims the interval after the
     * probe has already shown the port closed.
     */
    private static final Object LOCK = new Object();

    private static long lastAsk = 0L;
    /** Volatile so {@link #state()} reads it without taking the lock. */
    private static volatile String state = "not checked";
    /** Latest result Termux sent back for pin-adb-port.sh; null until one arrives. */
    private static volatile TermuxResult lastResult;
    /** Elapsed-realtime of the last ask and the last result, for fallback logging. */
    private static long lastAskAt = 0L;
    private static long lastResultAt = 0L;
    private static long fallbackLoggedAt = 0L;
    /** Termux < 0.109 cannot answer; log that once per process. */
    private static boolean oldTermuxLogged = false;
    /** Last pin event, so the 10 s probe does not repeat an unchanged reason. */
    private static String lastJournal = "";
    /** Each pending intent needs its own request code or only the first result arrives. */
    private static final AtomicInteger RESULT_REQUEST = new AtomicInteger(
            (int) (SystemClock.elapsedRealtime() & 0x7fffffff));

    private PinnedPort() {}

    public static String state() {
        return state;
    }

    /** The script's own last RESULT line, for the Status page and diagnostics. */
    public static String result() {
        TermuxResult r = lastResult;
        return r == null ? "none" : r.uiLine();
    }

    /** True when anything on the phone accepts a TCP connection on the port. */
    public static boolean isListening() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean hasTermux(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean termuxUsable(Context c) {
        return hasTermux(c)
                && c.checkSelfPermission(TERMUX_PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs a Termux script on the app's behalf; used by {@link WifiRadio}. */
    static boolean runScript(Context c, String script) {
        return askTermux(c, script);
    }

    /** Probe, and ask Termux when the port is closed. Safe from any thread. */
    public static String check(Context c) {
        return check(c, false);
    }

    /** As {@link #check(Context)}, ignoring the minimum interval. */
    public static String force(Context c) {
        return check(c, true);
    }

    private static String check(Context c, boolean force) {
        if (isListening()) {
            noteProbeFallback(c);
            TermuxResult r = lastResult;
            String state;
            if (isFresh(r) && r.isPinScript() && r.ok()) {
                state = setState("listening on " + PORT + "; script " + r.summary());
            } else {
                state = setState("listening on " + PORT);
            }
            journal(c, "info", "pin.state", state);
            return state;
        }
        if (!hasTermux(c)) {
            String state = setState("closed (Termux not installed; install it from F-Droid)");
            journal(c, "warn", "pin.termux_missing",
                    "Termux not installed; fix: install Termux from F-Droid, then set"
                            + " allow-external-apps=true");
            return state;
        }
        if (!termuxUsable(c)) {
            String fix = PermissionAudit.grantCommand(c.getPackageName(), TERMUX_PERM);
            String state = setState("closed (grant " + TERMUX_PERM + "; fix: " + fix + ")");
            journal(c, "warn", "pin.no_permission",
                    "Termux RUN_COMMAND not granted; fix: " + fix);
            return state;
        }
        TermuxResult fresh = lastResult;
        if (isFresh(fresh) && fresh.isPinScript()
                && DiagReasons.pinReason(fresh) == DiagReasons.PinReason.OK) {
            // The one case the result cannot see: the script completed but the
            // socket still does not answer. The probe stays the authority.
            journal(c, "warn", "pin.port_closed", "script reports pinned but 127.0.0.1:"
                    + PORT + " is closed: " + fresh.uiLine());
        }
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastAsk < MIN_INTERVAL_MS) {
                state = closedState("closed (asked Termux, waiting)");
                return state;
            }
            // Claim the interval before the intent leaves the process. A second
            // caller that also found the port closed now sees a fresh lastAsk
            // and does not ask Termux inside the same window. The intent is
            // sent below, outside this lock.
            lastAsk = now;
            lastAskAt = now;
        }
        // The ask itself is network/unbounded work; a failure still settles the
        // window so a refused background start is not retried in a tight loop.
        boolean asked = askTermux(c);
        synchronized (LOCK) {
            state = closedState(asked
                    ? "closed (asked Termux)" : "closed (could not reach Termux)");
        }
        if (!asked) {
            journal(c, "warn", "pin.run_refused",
                    "Termux RUN_COMMAND was refused; set allow-external-apps=true in"
                            + " ~/.termux/termux.properties and check "
                            + TERMUX_PERM);
        } else {
            journal(c, "info", "pin.waiting",
                    "asked Termux to pin port " + PORT + "; waiting for the port or result");
        }
        return state;
    }

    /** Journals a pin event once per distinct reason; the probe runs every 10 s. */
    private static void journal(Context c, String level, String code, String detail) {
        String key = level + " " + code + " " + detail;
        synchronized (LOCK) {
            if (key.equals(lastJournal)) return;
            lastJournal = key;
        }
        Diag.event(c, level, code, detail);
    }

    /**
     * Appends the last script failure to a "closed" state, so the UI shows the
     * reason the script printed instead of a bare "closed". The probe still
     * decides whether the port answers.
     */
    private static String closedState(String base) {
        TermuxResult r = lastResult;
        if (!isFresh(r) || !r.isPinScript()) return base;
        return base + "; script " + r.uiLine();
    }

    private static boolean isFresh(TermuxResult r) {
        return r != null && r.at > 0
                && SystemClock.elapsedRealtime() - r.at <= RESULT_FRESH_MS;
    }

    /**
     * Termux sends the result in {@link TermuxResultService}. This is called
     * from that service, on the process's main thread, and is the one place a
     * finished pin attempt is journalled: the state transition that follows
     * only repeats the same reason.
     */
    static void recordResult(Context c, TermuxResult r) {
        if (r == null || !r.isPinScript()) return;
        lastResult = r;
        synchronized (LOCK) {
            lastResultAt = SystemClock.elapsedRealtime();
        }
        if (r.reportsPinned()) {
            setState("listening on " + PORT + "; script " + r.summary());
        } else {
            setState("closed; script " + r.uiLine());
        }
        DiagReasons.PinReason reason = DiagReasons.pinReason(r);
        if (reason == DiagReasons.PinReason.OK) {
            journal(c, "info", "pin.result", r.journal());
        } else {
            journal(c, reason.level(), reason.code(), r.journal());
        }
    }

    /**
     * Termux before 0.109 ignores the pending-intent extra, so the port probe
     * is the only evidence the pin worked. Journal that once, rather than
     * silently behaving as before.
     */
    private static void noteProbeFallback(Context c) {
        long asked;
        long resultAt;
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            asked = lastAskAt;
            resultAt = lastResultAt;
            if (oldTermuxLogged || asked <= 0 || resultAt >= asked || now - asked < FALLBACK_MS
                    || fallbackLoggedAt >= asked) {
                return;
            }
            fallbackLoggedAt = asked;
        }
        Diag.warn(c, "pin.fallback", "port answers but Termux sent no result for the"
                + " last ask (Termux < 0.109?); the port probe is the fallback");
    }

    private static String setState(String value) {
        synchronized (LOCK) {
            state = value;
            return value;
        }
    }

    private static boolean askTermux(Context c) {
        return askTermux(c, SCRIPT);
    }

    private static boolean askTermux(Context c, String script) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PKG, RUN_SERVICE);
        intent.setAction(RUN_ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", script);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[0]);
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        // 0 = do not touch sessions, the script runs without a terminal.
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        // Without this, the only evidence is a port probe after the fact.
        intent.putExtra(EXTRA_PENDING_INTENT, resultPendingIntent(c, script));
        if (!supportsResult(c)) {
            logOldTermux(c);
        }
        try {
            c.startService(intent);
            Log.i(Ensurer.TAG, "asked Termux to run " + script);
            return true;
        } catch (Exception e) {
            // Android 8+ refuses a background service start; only the monitor
            // service (a foreground service) and the UI are allowed to ask.
            Log.w(Ensurer.TAG, "pin: Termux not started from this state: " + e);
            return false;
        }
    }

    /**
     * One-shot pending intent for {@link TermuxResultService}. The request code
     * has to differ per execution: with the same code, Termux's second result
     * would land on a consumed one-shot intent. FLAG_MUTABLE is required so
     * Termux can attach the result bundle it builds.
     */
    private static PendingIntent resultPendingIntent(Context c, String script) {
        Intent result = new Intent(c, TermuxResultService.class);
        result.putExtra(TermuxResultService.EXTRA_SCRIPT, script);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getService(c, RESULT_REQUEST.getAndIncrement(), result, flags);
    }

    /** Termux 0.109 added the result bundle; older builds ignore the extra. */
    private static boolean supportsResult(Context c) {
        String version = termuxVersion(c);
        int minor = minorOf(version);
        return minor < 0 || minor >= 109;
    }

    private static void logOldTermux(Context c) {
        synchronized (LOCK) {
            if (oldTermuxLogged) return;
            oldTermuxLogged = true;
        }
        Diag.warn(c, "pin.fallback", "Termux " + termuxVersion(c)
                + " predates RUN_COMMAND results; the port probe is the fallback");
    }

    private static String termuxVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(TERMUX_PKG, 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** "0.118.1" and "0.109" both mean 118 and 109; -1 when unparseable. */
    static int minorOf(String version) {
        if (version == null) return -1;
        String[] parts = version.split("\\.");
        if (parts.length < 2) return -1;
        String digits = parts[1].replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

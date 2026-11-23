package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

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
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    /** Copied there by the checkout's install step; see README. */
    private static final String SCRIPT = TERMUX_HOME + "/wireless-adb-keeper/pin-adb-port.sh";

    /** Asked again only after this long, so a stuck adbd is not hammered. */
    private static final long MIN_INTERVAL_MS = 30_000L;

    /**
     * Guards {@link #lastAsk} and {@link #state}. Never held across a socket
     * probe or a Termux intent: the lock only claims the interval after the
     * probe has already shown the port closed.
     */
    private static final Object LOCK = new Object();

    private static long lastAsk = 0L;
    /** Volatile so {@link #state()} reads it without taking the lock. */
    private static volatile String state = "not checked";

    private PinnedPort() {}

    public static String state() {
        return state;
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
            return setState("listening on " + PORT);
        }
        if (!hasTermux(c)) {
            return setState("closed (Termux not installed)");
        }
        if (!termuxUsable(c)) {
            return setState("closed (grant " + TERMUX_PERM + ")");
        }
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastAsk < MIN_INTERVAL_MS) {
                state = "closed (asked Termux, waiting)";
                return state;
            }
            // Claim the interval before the intent leaves the process. A second
            // caller that also found the port closed now sees a fresh lastAsk
            // and does not ask Termux inside the same window. The intent is
            // sent below, outside this lock.
            lastAsk = now;
        }
        // The ask itself is network/unbounded work; a failure still settles the
        // window so a refused background start is not retried in a tight loop.
        boolean asked = askTermux(c);
        synchronized (LOCK) {
            state = asked ? "closed (asked Termux)" : "closed (could not reach Termux)";
            return state;
        }
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
}

package io.github.mbanucu.wirelessadbkeeper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

public final class Ensurer {
    public static final String TAG = "WifiDebug";
    public static final String KEY_ADB_WIFI = "adb_wifi_enabled";
    public static final String KEY_DEV_SETTINGS = "development_settings_enabled";
    public static final String PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;

    /**
     * Set after we write the setting ourselves. The change notification for our
     * own write arrives while the value may still read as 0, which used to cause
     * a write/notify loop that restarted adbd several times per reconnect.
     */
    public static volatile long quietUntil = 0L;
    private static final long QUIET_MS = 3000L;

    private Ensurer() {}

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isWifiConnected(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        // The default route is often mobile data while a local-only Wi-Fi link is
        // up, so check every network instead of just the active one.
        try {
            Network[] networks = cm.getAllNetworks();
            if (networks != null) {
                for (Network n : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static boolean isWirelessDebuggingOn(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), KEY_ADB_WIFI, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static String ensure(Context c) {
        return ensure(c, "on demand");
    }

    /**
     * As {@link #ensure(Context)}, naming the trigger for the journal.
     *
     * <p>The result is journalled only when it changes: this runs on the
     * monitor's timer and from a periodic job, so an unchanged "already
     * enabled" every minute would push every useful entry out of the ring.
     */
    public static String ensure(Context c, String reason) {
        if (!hasPermission(c)) {
            Log.w(TAG, "ensure: missing WRITE_SECURE_SETTINGS");
            return record(c, reason, EnsureDecision.Reason.NO_PERMISSION);
        }
        if (!isWifiConnected(c)) {
            Log.i(TAG, "ensure: no Wi-Fi, skipping");
            return record(c, reason, EnsureDecision.Reason.NO_WIFI);
        }
        ContentResolver cr = c.getContentResolver();
        try {
            boolean devSettingsOn = Settings.Global.getInt(cr, KEY_DEV_SETTINGS, 0) != 0;
            boolean adbOn = Settings.Global.getInt(cr, KEY_ADB_WIFI, 0) == 1;
            EnsureDecision.Reason decision = EnsureDecision.decide(
                    new EnsureDecision.Inputs(true, true, true, devSettingsOn, adbOn));
            if (!devSettingsOn) {
                Settings.Global.putInt(cr, KEY_DEV_SETTINGS, 1);
                Log.i(TAG, "enabled developer settings");
            }
            if (decision == EnsureDecision.Reason.CHANGED) {
                Settings.Global.putInt(cr, KEY_ADB_WIFI, 1);
                quietUntil = SystemClock.elapsedRealtime() + QUIET_MS;
                Log.i(TAG, "enabled wireless debugging");
            } else {
                Log.i(TAG, "ensure: already enabled");
            }
            return record(c, reason, decision);
        } catch (Exception e) {
            Log.e(TAG, "ensure failed", e);
            return record(c, reason, EnsureDecision.Reason.FAILED, "Error: " + e);
        }
    }

    /** Last journalled ensure result, so a repeated one does not flood the log. */
    private static volatile String lastRecorded = "";

    private static String record(Context c, String reason, EnsureDecision.Reason decision) {
        return record(c, reason, decision, decision.result());
    }

    private static String record(Context c, String reason, EnsureDecision.Reason decision,
                                 String result) {
        if (!result.equals(lastRecorded)) {
            lastRecorded = result;
            String detail = reason + ": " + result;
            if ("warn".equals(decision.level())) {
                Diag.warn(c, decision.code(), detail);
            } else if ("error".equals(decision.level())) {
                Diag.error(c, decision.code(), detail);
            } else {
                Diag.info(c, decision.code(), detail);
            }
        }
        return result;
    }
}

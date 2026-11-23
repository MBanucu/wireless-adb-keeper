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
    /** Component that walks the vendor autostart list for the boot scripts. */
    public static final String A11Y_COMPONENT =
            "org.phonelink.autotoggle/org.phonelink.autotoggle.ToggleService";

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

    /**
     * The accessibility grant is wiped on reboot, and it is what lets the
     * autostart toggler drive the vendor list at all, so it is restored here
     * on every boot rather than by hand.
     */
    static void ensureAccessibility(Context c) {
        ContentResolver cr = c.getContentResolver();
        try {
            String enabled = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(A11Y_COMPONENT)) {
                String merged = enabled == null || enabled.trim().isEmpty()
                        ? A11Y_COMPONENT
                        : enabled + ":" + A11Y_COMPONENT;
                Settings.Secure.putString(
                        cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
                Log.i(TAG, "enabled autostart accessibility service");
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not enable accessibility", e);
        }
    }

    public static String ensure(Context c) {
        if (!hasPermission(c)) {
            Log.w(TAG, "ensure: missing WRITE_SECURE_SETTINGS");
            return "Missing WRITE_SECURE_SETTINGS permission";
        }
        ensureAccessibility(c);
        if (!isWifiConnected(c)) {
            Log.i(TAG, "ensure: no Wi-Fi, skipping");
            return "Wi-Fi not connected";
        }
        ContentResolver cr = c.getContentResolver();
        try {
            if (Settings.Global.getInt(cr, KEY_DEV_SETTINGS, 0) == 0) {
                Settings.Global.putInt(cr, KEY_DEV_SETTINGS, 1);
                Log.i(TAG, "enabled developer settings");
            }
            if (Settings.Global.getInt(cr, KEY_ADB_WIFI, 0) != 1) {
                Settings.Global.putInt(cr, KEY_ADB_WIFI, 1);
                quietUntil = SystemClock.elapsedRealtime() + QUIET_MS;
                Log.i(TAG, "enabled wireless debugging");
                return "Enabled wireless debugging";
            }
            Log.i(TAG, "ensure: already enabled");
            return "Already enabled";
        } catch (Exception e) {
            Log.e(TAG, "ensure failed", e);
            return "Error: " + e;
        }
    }
}

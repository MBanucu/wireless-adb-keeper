package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

/**
 * Turns the Wi-Fi radio on.
 *
 * Apps lost the right to toggle Wi-Fi in Android 10, so the only in-app routes
 * are the settings panel (a tap by the user) or asking Termux, whose own adb
 * client runs as the shell user where `svc wifi enable` still works. The app
 * already depends on Termux for pinning adbd to port 5555, so it uses the same
 * route here and falls back to the panel when Termux cannot help.
 */
public final class WifiRadio {
    /** Copied next to pin-adb-port.sh by the checkout's install step. */
    private static final String SCRIPT =
            "/data/data/com.termux/files/home/wireless-adb-keeper/wifi-on.sh";

    /** Latest RESULT line Termux sent back for wifi-on.sh. */
    private static volatile TermuxResult lastResult;

    private WifiRadio() {}

    /** The script's own last RESULT line, or "none" before one arrives. */
    public static String result() {
        TermuxResult r = lastResult;
        return r == null ? "none" : r.uiLine();
    }

    static void recordResult(TermuxResult r) {
        if (r == null || !r.isWifiScript()) return;
        lastResult = r;
    }

    /**
     * Asks for Wi-Fi to be switched on.
     *
     * @return a line to show the user, or {@code null} when the caller should
     *         open the Wi-Fi panel instead.
     */
    @SuppressWarnings("deprecation")
    public static String enable(Context c) {
        if (Build.VERSION.SDK_INT < 29) {
            WifiManager wifi = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.setWifiEnabled(true)) {
                return "Wi-Fi turning on";
            }
        }
        if (PinnedPort.termuxUsable(c)) {
            return PinnedPort.runScript(c, SCRIPT)
                    ? "Asked Termux to turn Wi-Fi on"
                    : "Could not reach Termux";
        }
        return null;
    }

    /** The settings screen that lets the user switch Wi-Fi on by hand. */
    public static Intent panel() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 29) {
            intent = new Intent(Settings.Panel.ACTION_WIFI);
        } else {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}

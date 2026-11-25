package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Turns the Wi-Fi radio on.
 *
 * Apps lost the right to toggle Wi-Fi in Android 10. The canonical Gradle build
 * therefore opens the app's own trusted TLS connection and sends
 * {@code shell:svc wifi enable}, which works before Termux is paired or even
 * installed. Termux stays the fallback for an install whose app key is not
 * paired and for the dependency-free {@code build.sh} APK, and the Wi-Fi panel
 * remains the last resort for a tap by the user.
 *
 * <p>The journal names the client that took the request ({@link WifiRoute}),
 * so a failed Wi-Fi-on is not ambiguous between the app key and Termux.
 */
public final class WifiRadio {
    /** Copied next to pin-adb-port.sh by the checkout's install step. */
    private static final String SCRIPT =
            "/data/data/com.termux/files/home/wireless-adb-keeper/wifi-on.sh";
    /** The Gradle-only transport, reached by name so build.sh still compiles. */
    private static final String TLS_WIFI_CLASS =
            "io.github.mbanucu.wirelessadbkeeper.TlsWifiTransport";

    /** Latest RESULT line Termux sent back for wifi-on.sh. */
    private static volatile TermuxResult lastResult;
    /** The last client that took a Wi-Fi-on request; "none" before one does. */
    private static volatile String lastClient = "none";

    private WifiRadio() {}

    /** The script's own last RESULT line, or "none" before one arrives. */
    public static String result() {
        TermuxResult r = lastResult;
        return r == null ? "none" : r.uiLine();
    }

    /** The client that last took a Wi-Fi-on request, or "none". */
    public static String client() {
        return lastClient;
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
    public static String enable(Context c) {
        WifiTransport tls = tlsTransport(c);
        boolean termux = PinnedPort.termuxUsable(c);
        for (WifiRoute.Client route : WifiRoute.order(Build.VERSION.SDK_INT < 29,
                tls != null, termux)) {
            switch (route) {
                case WIFI_MANAGER:
                    if (enableWithManager(c)) {
                        took(c, route, "setWifiEnabled(true)");
                        return "Wi-Fi turning on";
                    }
                    break;
                case APP_TLS:
                    if (tls.enable()) {
                        took(c, route, "sent `svc wifi enable` over the app's trusted TLS key");
                        return "Asked the app's ADB key to turn Wi-Fi on";
                    }
                    // Pairing journals the specific reason (untrusted, timeout,
                    // or no endpoint); fall through to the Termux fallback.
                    break;
                case TERMUX:
                    if (PinnedPort.runScript(c, SCRIPT)) {
                        took(c, route, "asked Termux to run wifi-on.sh");
                        return "Asked Termux to turn Wi-Fi on";
                    }
                    String fix = "set allow-external-apps=true in ~/.termux/termux.properties; "
                            + PermissionAudit.grantCommand(c.getPackageName(),
                                    PermissionAudit.RUN_COMMAND);
                    Diag.warn(c, "wifi.on.run_refused",
                            "Termux RUN_COMMAND was refused; fix: " + fix);
                    return "Could not reach Termux; fix: " + fix;
                case PANEL:
                    lastClient = route.label();
                    Diag.verbose(c, VerboseLog.WIFI,
                            "wifi client=panel no transport took the request");
                    break;
            }
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

    @SuppressWarnings("deprecation")
    private static boolean enableWithManager(Context c) {
        WifiManager wifi = (WifiManager) c.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.setWifiEnabled(true);
    }

    /**
     * Names the client in the journal and diagnostics. The request itself has
     * only left the app; whether the radio ends up on is still Android's job.
     */
    private static void took(Context c, WifiRoute.Client route, String detail) {
        lastClient = route.label();
        Diag.info(c, route.journalCode(), detail);
        Diag.verbose(c, VerboseLog.WIFI, "wifi client=" + route.label() + " " + detail);
    }

    /**
     * Loads the Gradle-only in-app transport. The dependency-free build has no
     * such class, so the Termux fallback below is all that remains there.
     */
    private static WifiTransport tlsTransport(Context c) {
        try {
            Class<?> cls = Class.forName(TLS_WIFI_CLASS);
            return (WifiTransport) cls.getConstructor(Context.class).newInstance(c);
        } catch (Throwable e) {
            Log.d(Ensurer.TAG, "no in-app Wi-Fi transport: " + e);
            return null;
        }
    }
}

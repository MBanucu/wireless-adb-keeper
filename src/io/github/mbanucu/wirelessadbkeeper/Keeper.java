package io.github.mbanucu.wirelessadbkeeper;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every action the UI offers, as a plain method.
 *
 * The activity and {@link ControlReceiver} both call these, so a button and an
 * `am broadcast` from adb do exactly the same thing instead of drifting apart.
 */
public final class Keeper {
    private Keeper() {}

    /** Re-applies wireless debugging now; safe to call at any time. */
    public static String enable(Context c) {
        return Ensurer.ensure(c);
    }

    /** Switches the Wi-Fi radio on, through Termux when that is possible. */
    public static String wifiOn(Context c) {
        String result = WifiRadio.enable(c);
        return result == null
                ? "error=Termux unavailable; Wi-Fi must be switched on by hand"
                : "result=" + result;
    }

    /** Starts or stops the monitor foreground service. */
    public static String service(Context c, boolean start) {
        if (!start) {
            c.stopService(new Intent(c, MonitorService.class));
            return "service=stopping";
        }
        try {
            Intent intent = new Intent(c, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(intent);
            } else {
                c.startService(intent);
            }
            return "service=starting";
        } catch (Exception e) {
            // Android 12+ blocks a background app from starting its own
            // foreground service; from adb the shell can do it explicitly.
            if (String.valueOf(e.getMessage()).contains("not allowed")) {
                return "error=background start refused; run: adb shell am start-foreground-service"
                        + " -n " + c.getPackageName() + "/.MonitorService";
            }
            return "error=" + e;
        }
    }

    /** Asks Android to rejoin the saved network now. */
    public static String connectNow(Context c) {
        boolean auto = LocalWifi.isAutoMode(c);
        if (!auto && !LocalWifi.supported()) {
            return "error=local-only mode needs Android 10 or newer";
        }
        if (!auto && !LocalWifi.hasLocationPermission(c)) {
            return "error=location not granted; adb shell pm grant "
                    + c.getPackageName() + " " + LocalWifi.PERM_LOCATION;
        }
        if (!sendServiceAction(c, MonitorService.ACTION_CONNECT_LOCAL)) {
            return "error=monitor service is not running; start it first";
        }
        return auto ? "connect=suggestion requested"
                : "connect=local link requested";
    }

    /** Switches between auto-connect and local-only; null mode toggles. */
    public static String setMode(Context c, String mode) {
        boolean auto;
        if (mode == null || mode.isEmpty()) {
            auto = !LocalWifi.isAutoMode(c);
        } else if (LocalWifi.MODE_AUTO.equalsIgnoreCase(mode)) {
            auto = true;
        } else if (LocalWifi.MODE_LOCAL.equalsIgnoreCase(mode)) {
            auto = false;
        } else {
            return "error=mode must be auto or local";
        }
        LocalWifi.setMode(c, auto ? LocalWifi.MODE_AUTO : LocalWifi.MODE_LOCAL);
        sendServiceAction(c, MonitorService.ACTION_RELOAD_LOCAL);
        return "mode=" + (auto ? "auto-connect" : "local-only");
    }

    /** Stores the credentials; the password is never echoed back. */
    public static String saveNetwork(Context c, String ssid, String passphrase) {
        if (ssid == null || ssid.trim().isEmpty()) {
            return "error=ssid is required";
        }
        LocalWifi.setSsid(c, ssid.trim());
        if (passphrase != null) {
            LocalWifi.setPassphrase(c, passphrase);
        }
        sendServiceAction(c, MonitorService.ACTION_RELOAD_LOCAL);
        return "ssid=" + LocalWifi.ssid(c)
                + ", passphrase=" + (LocalWifi.passphrase(c).isEmpty() ? "none" : "set");
    }

    /** Sends `adb tcpip 5555` through Termux right now. */
    public static String pinPort(Context c) {
        return "pinned_port=" + PinnedPort.force(c);
    }

    /**
     * The app's ADB public key and where it is kept.
     *
     * This is the side of pairing that has to exist before the pairing client
     * does: the key is generated once, in device-protected storage, and its
     * public half is what a pairing code would authorise in adb_keys.
     */
    public static String pubkey(Context c) {
        try {
            AdbKey key = AdbIdentity.load(c);
            return "pubkey=" + key.publicKeyBase64()
                    + "\nname=" + AdbIdentity.KEY_NAME
                    + "\npath=" + new File(AdbIdentity.dir(c), AdbIdentity.KEY_FILE);
        } catch (IOException e) {
            return "error=" + e.getMessage();
        }
    }

    /** Registers the 15-minute JobScheduler fallback. */
    public static String schedule(Context c) {
        MainActivity.scheduleJobs(c);
        return "job=" + (jobScheduled(c) ? "scheduled" : "could not schedule");
    }

    /**
     * Everything the Status page shows, in the same words, plus the bits that
     * are easier to read as text than from a screenshot.
     */
    public static Map<String, String> status(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("permission", Ensurer.hasPermission(c) ? "granted" : "not granted");
        if (LocalWifi.supported()) {
            m.put("location", LocalWifi.hasLocationPermission(c) ? "granted" : "not granted");
        }
        m.put("wifi", Ensurer.isWifiConnected(c) ? "connected" : "not connected");
        m.put("wireless", Ensurer.isWirelessDebuggingOn(c) ? "on" : "off");
        m.put("service", MonitorService.running ? "running" : "stopped");
        m.put("pinned_port", PinnedPort.state());
        m.put("mode", LocalWifi.isAutoMode(c) ? "auto-connect" : "local-only");
        if (!LocalWifi.isAutoMode(c)) {
            m.put("local_link", MonitorService.running ? LocalWifi.state : "off");
        }
        String detail = MonitorService.running ? LocalWifi.detail : null;
        if (detail != null && !detail.isEmpty()) {
            m.put("last_result", detail);
        }
        m.put("job", jobScheduled(c) ? "scheduled" : "not scheduled");
        m.put("termux", PinnedPort.termuxUsable(c) ? "usable"
                : PinnedPort.hasTermux(c) ? "no RUN_COMMAND grant" : "not installed");
        m.put("ssid", LocalWifi.ssid(c).isEmpty() ? "(none)" : LocalWifi.ssid(c));
        m.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
        return m;
    }

    public static boolean jobScheduled(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return false;
        for (JobInfo info : js.getAllPendingJobs()) {
            if (info.getId() == BootReceiver.PERIODIC_JOB) return true;
        }
        return false;
    }

    private static boolean sendServiceAction(Context c, String action) {
        Intent intent = new Intent(c, MonitorService.class);
        intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(intent);
            } else {
                c.startService(intent);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

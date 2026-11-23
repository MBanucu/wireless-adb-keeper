package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The app's own record of what happened, readable on the phone.
 *
 * <p>logcat is useless to the person holding the device: it needs a laptop, it
 * is mixed with system noise, and it is gone after the next reboot. This keeps a
 * small journal next to the ADB key in device-protected storage, so the events
 * from a boot - including the ones before the first unlock - are still there
 * afterwards, and so the Diagnostics page and `content call --method diag` can
 * show them without any special tooling.
 *
 * <p>Every entry is one line: {@code time uptime level code detail}. The level
 * is info, warn or error; the code is a stable {@code area.action} string
 * (`boot.received`, `svc.start`, `ensure.changed`, `pin.state`, `pin.result`,
 * `pin.fallback`, `wifi.suggest.added`, ...) so a report can be searched and a
 * test can assert on it instead of on prose.
 *
 * <p>Two rules are enforced here rather than trusted: the journal is capped
 * (events and bytes) and it never throws. A diagnostics path that fills the
 * disk or takes the service down is worse than no diagnostics, so every failure
 * in this class is reduced to one log line.
 *
 * <p>The passphrase, the control token and a pairing code must never be passed
 * in. {@link #scrub} is a second line of defence for the one shape that is easy
 * to leak by accident (`passphrase=...`), not a licence to include secrets.
 */
public final class Diag {
    static final String DIR = "diagnostics";
    static final String FILE = "events.log";
    /** Hard caps: whichever is reached first wins. */
    static final int MAX_EVENTS = 200;
    static final long MAX_BYTES = 32 * 1024;

    private static final Object LOCK = new Object();
    private static final String TIME = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    private Diag() {}

    public static void info(Context c, String code, String detail) {
        event(c, "info", code, detail);
    }

    public static void warn(Context c, String code, String detail) {
        event(c, "warn", code, detail);
    }

    public static void error(Context c, String code, String detail) {
        event(c, "error", code, detail);
    }

    /** Appends one entry. Never throws; a broken journal only costs the entry. */
    public static void event(Context c, String level, String code, String detail) {
        if (c == null || code == null) return;
        String line = format(level, code, detail);
        try {
            if ("warn".equals(level)) {
                Log.w(Ensurer.TAG, "diag: " + line);
            } else if ("error".equals(level)) {
                Log.e(Ensurer.TAG, "diag: " + line);
            } else {
                Log.i(Ensurer.TAG, "diag: " + line);
            }
            synchronized (LOCK) {
                File file = file(c);
                ArrayDeque<String> lines = read(file);
                lines.addLast(line);
                trim(lines);
                write(file, lines);
            }
        } catch (Throwable t) {
            Log.w(Ensurer.TAG, "diag: cannot record " + code + ": " + t);
        }
    }

    /** The journal, oldest first, one entry per line. Empty when nothing ran yet. */
    public static String journal(Context c) {
        if (c == null) return "";
        try {
            synchronized (LOCK) {
                StringBuilder out = new StringBuilder();
                for (String line : read(file(c))) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
                return out.toString();
            }
        } catch (Throwable t) {
            return "journal unavailable: " + t;
        }
    }

    /** How many entries the journal holds, for the status line. */
    public static int count(Context c) {
        if (c == null) return 0;
        try {
            synchronized (LOCK) {
                return read(file(c)).size();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The current state as `key=value` lines.
     *
     * <p>Each field is read independently and report "unavailable" on its own:
     * before the first unlock the credential-protected Wi-Fi prefs cannot be
     * read, and that must not take the whole snapshot down - it is exactly the
     * situation this page exists to describe.
     */
    public static String status(Context c) {
        if (c == null) return "";
        StringBuilder out = new StringBuilder();
        put(out, "app_version", new Value() {
            @Override
            public String get() throws Exception {
                return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            }
        });
        put(out, "android", () -> Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        put(out, "uptime_s", () -> String.valueOf(SystemClock.elapsedRealtime() / 1000));
        put(out, "process_started_after_boot_s",
                () -> String.valueOf(Process.getStartElapsedRealtime() / 1000));
        put(out, "unlocked", () -> String.valueOf(unlocked(c)));
        put(out, "mode", () -> LocalWifi.mode(c));
        put(out, "service", () -> MonitorService.running ? "running" : "stopped");
        put(out, "adb_wifi_enabled", () -> String.valueOf(Ensurer.isWirelessDebuggingOn(c)));
        put(out, "dev_settings", new Value() {
            @Override
            public String get() throws Exception {
                return String.valueOf(Settings.Global.getInt(
                        c.getContentResolver(), Ensurer.KEY_DEV_SETTINGS, 0) == 1);
            }
        });
        put(out, "wifi_connected", () -> String.valueOf(Ensurer.isWifiConnected(c)));
        put(out, "port_5555", PinnedPort::state);
        put(out, "pin_result", PinnedPort::result);
        put(out, "wifi_result", WifiRadio::result);
        put(out, "termux_installed", () -> String.valueOf(PinnedPort.hasTermux(c)));
        put(out, "termux_grant", () -> String.valueOf(PinnedPort.termuxUsable(c)));
        put(out, "last_result", () -> LocalWifi.detail == null || LocalWifi.detail.isEmpty()
                ? "none" : LocalWifi.detail);
        put(out, "perm_write_secure_settings", () -> permission(c,
                android.Manifest.permission.WRITE_SECURE_SETTINGS));
        put(out, "perm_location", () -> LocalWifi.supported()
                ? permission(c, LocalWifi.PERM_LOCATION) : "not needed before Android 10");
        put(out, "perm_notifications", () -> permission(c, "android.permission.POST_NOTIFICATIONS"));
        put(out, "key_present", () -> String.valueOf(
                new File(AdbIdentity.dir(c), AdbIdentity.KEY_FILE).isFile()));
        put(out, "journal_events", () -> count(c) + " entries");
        put(out, "journal_bytes", () -> String.valueOf(file(c).length()));
        return out.toString();
    }

    /** Status followed by the journal, for `content call --method diag`. */
    public static String report(Context c) {
        return status(c) + "\n-- journal (oldest first) --\n" + journal(c) + "\n";
    }

    // ---------------------------------------------------------------- internals

    private interface Value {
        String get() throws Exception;
    }

    private static void put(StringBuilder out, String key, Value value) {
        String v;
        try {
            v = value.get();
        } catch (Throwable t) {
            v = "unavailable: " + t.getClass().getSimpleName();
        }
        out.append(key).append('=').append(oneLine(v)).append('\n');
    }

    private static String permission(Context c, String name) {
        if (Build.VERSION.SDK_INT < 23) return "n/a";
        return c.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
                ? "granted" : "not granted";
    }

    private static boolean unlocked(Context c) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    private static String format(String level, String code, String detail) {
        long uptime = SystemClock.elapsedRealtime() / 1000;
        SimpleDateFormat f = new SimpleDateFormat(TIME, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date()) + " " + uptime + "s " + level + " " + code
                + (detail == null || detail.isEmpty() ? "" : " " + scrub(detail));
    }

    /** Removes the one secret shape that is easy to include by accident. */
    static String scrub(String detail) {
        String out = detail.replaceAll("(?i)passphrase=\\S+", "passphrase=***");
        out = out.replaceAll("(?i)token=\\S+", "token=***");
        out = out.replaceAll("(?i)pairing_code=\\S+", "pairing_code=***");
        return out.replace('\n', ' ').replace('\r', ' ');
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    /** The journal file, in device-protected storage so boot events survive. */
    static File file(Context c) {
        Context direct = c.createDeviceProtectedStorageContext();
        File dir = new File(direct.getFilesDir(), DIR);
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE);
    }

    private static ArrayDeque<String> read(File file) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (!file.isFile()) return lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) lines.addLast(line);
            }
        } catch (Exception e) {
            Log.w(Ensurer.TAG, "diag: cannot read journal: " + e);
        }
        return lines;
    }

    private static void write(File file, ArrayDeque<String> lines) throws Exception {
        try (OutputStreamWriter out = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        }
    }

    /** Enforces both caps from the front: the newest entries are the useful ones. */
    private static void trim(ArrayDeque<String> lines) {
        while (lines.size() > MAX_EVENTS) {
            lines.removeFirst();
        }
        long bytes = 0;
        for (String line : lines) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        while (bytes > MAX_BYTES && lines.size() > 1) {
            String removed = lines.removeFirst();
            bytes -= removed.getBytes(StandardCharsets.UTF_8).length + 1;
        }
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 * (the default is 200 events and 32 KiB; the page can choose "last day" or
 * off) and it never throws. A diagnostics path that fills the disk or takes the
 * service down is worse than no diagnostics, so every failure in this class is
 * reduced to one log line. Off returns before the file is opened, so it stops
 * growth instead of emptying the journal.
 *
 * <p>An update keeps the last journal as {@code events.previous.log} with its
 * version and boot id, so the page never mixes the previous build's lines with
 * this boot's.
 *
 * <p>The passphrase, the control token and a pairing code must never be passed
 * in. {@link #scrub} is a second line of defence for the one shape that is easy
 * to leak by accident (`passphrase=...`), not a licence to include secrets.
 */
public final class Diag {
    static final String DIR = "diagnostics";
    static final String FILE = DiagRotation.CURRENT_FILE;

    private static final Object LOCK = new Object();

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

    /**
     * One verbose-only event, when the opt-in window allows it.
     *
     * <p>The high-frequency paths (every pin tick, observer change, network
     * transition and notification update) call this instead of {@link #info}.
     * With the window off nothing is written, so the journal behaves exactly as
     * before. When the window ends, {@link VerboseLog} journals
     * {@code diag.verbose.expired} once and silently drops the rest.
     */
    public static void verbose(Context c, String code, String detail) {
        if (c == null) return;
        VerboseLog.emit(VerboseLogStore.of(c), System.currentTimeMillis(), code, detail,
                (level, eventCode, eventDetail) ->
                        event(c, level, eventCode, eventDetail));
    }

    /** Appends one entry. Never throws; a broken journal only costs the entry. */
    public static void event(Context c, String level, String code, String detail) {
        if (c == null || code == null) return;
        try {
            DiagRetention.Mode mode = DiagRetentionStore.mode(c);
            if (!DiagRetention.writes(mode)) return;
            synchronized (LOCK) {
                File file = file(c);
                String version = null;
                try {
                    version = version(c);
                } catch (Throwable ignored) {
                    // A journal entry is still more useful than none; only the
                    // rotation needs the version.
                }
                if (version != null) {
                    DiagRotation.maybeRotate(file.getParentFile(), version, bootId(c));
                }
                String line = DiagJournal.append(file, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime() / 1000, level, code, detail, mode);
                if (line == null) return;
                if ("warn".equals(level)) {
                    Log.w(Ensurer.TAG, "diag: " + line);
                } else if ("error".equals(level)) {
                    Log.e(Ensurer.TAG, "diag: " + line);
                } else {
                    Log.i(Ensurer.TAG, "diag: " + line);
                }
            }
        } catch (Throwable t) {
            Log.w(Ensurer.TAG, "diag: cannot record " + code + ": " + t);
        }
    }

    /** The journal, oldest first, one entry per line. Empty when nothing ran yet. */
    public static String journal(Context c) {
        if (c == null) return "";
        try {
            rotateIfNeeded(c);
            synchronized (LOCK) {
                return DiagJournal.journal(file(c));
            }
        } catch (Throwable t) {
            return "journal unavailable: " + t;
        }
    }

    /** The one journal kept from before the last app update; empty when none. */
    public static String previousJournal(Context c) {
        if (c == null) return "";
        try {
            rotateIfNeeded(c);
            synchronized (LOCK) {
                return DiagRotation.previousJournal(file(c).getParentFile());
            }
        } catch (Throwable t) {
            return "previous journal unavailable: " + t;
        }
    }

    /** The label for the previous build's lines, or "" when there is none. */
    public static String previousMarker(Context c) {
        if (c == null) return "";
        try {
            rotateIfNeeded(c);
            synchronized (LOCK) {
                return DiagRotation.previousMarker(file(c).getParentFile());
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** How many entries the journal holds, for the status line. */
    public static int count(Context c) {
        if (c == null) return 0;
        try {
            rotateIfNeeded(c);
            synchronized (LOCK) {
                return DiagJournal.count(file(c));
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
        rotateIfNeeded(c);
        List<DiagSnapshot.Field> fields = new ArrayList<>();
        fields.add(new DiagSnapshot.Field("app_version", () -> version(c)));
        fields.add(new DiagSnapshot.Field("android",
                () -> Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"));
        fields.add(new DiagSnapshot.Field("boot_id", () -> bootId(c)));
        fields.add(new DiagSnapshot.Field("uptime_s",
                () -> String.valueOf(SystemClock.elapsedRealtime() / 1000)));
        fields.add(new DiagSnapshot.Field("process_started_after_boot_s",
                () -> String.valueOf(Process.getStartElapsedRealtime() / 1000)));
        fields.add(new DiagSnapshot.Field("unlocked", () -> String.valueOf(unlocked(c))));
        fields.add(new DiagSnapshot.Field("mode", () -> LocalWifi.mode(c)));
        fields.add(new DiagSnapshot.Field("service",
                () -> MonitorService.running ? "running" : "stopped"));
        fields.add(new DiagSnapshot.Field("verbose", () -> VerboseLog.status(
                VerboseLogStore.of(c), System.currentTimeMillis())));
        fields.add(new DiagSnapshot.Field("adb_wifi_enabled",
                () -> String.valueOf(Ensurer.isWirelessDebuggingOn(c))));
        fields.add(new DiagSnapshot.Field("dev_settings", () -> {
            try {
                return String.valueOf(Settings.Global.getInt(
                        c.getContentResolver(), Ensurer.KEY_DEV_SETTINGS, 0) == 1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        fields.add(new DiagSnapshot.Field("wifi_connected",
                () -> String.valueOf(Ensurer.isWifiConnected(c))));
        fields.add(new DiagSnapshot.Field("port_5555", PinnedPort::state));
        fields.add(new DiagSnapshot.Field("pin_result", PinnedPort::result));
        fields.add(new DiagSnapshot.Field("wifi_result", WifiRadio::result));
        fields.add(new DiagSnapshot.Field("last_result",
                () -> LocalWifi.detail == null || LocalWifi.detail.isEmpty()
                        ? "none" : LocalWifi.detail));
        fields.addAll(PermissionAudit.fields(new PermissionAudit.Inputs(
                c.getPackageName(), Build.VERSION.SDK_INT,
                Ensurer.hasPermission(c), LocalWifi.hasLocationPermission(c),
                PinnedPort.termuxUsable(c),
                granted(c, PermissionAudit.POST_NOTIFICATIONS),
                PinnedPort.hasTermux(c))));
        fields.add(new DiagSnapshot.Field("key_present", () -> String.valueOf(
                new File(AdbIdentity.dir(c), AdbIdentity.KEY_FILE).isFile())));
        fields.add(new DiagSnapshot.Field("key_certificate", () -> String.valueOf(
                new File(AdbIdentity.dir(c), AdbCertificate.CERT_FILE).isFile())));
        fields.add(new DiagSnapshot.Field("journal_events",
                () -> count(c) + " entries"));
        fields.add(new DiagSnapshot.Field("journal_bytes",
                () -> String.valueOf(file(c).length())));
        fields.add(new DiagSnapshot.Field("journal_retention",
                () -> DiagRetentionStore.mode(c).label()));
        fields.add(new DiagSnapshot.Field("journal_previous", () -> {
            String marker = previousMarker(c);
            return marker.isEmpty() ? "none" : marker;
        }));
        return DiagSnapshot.render(fields);
    }

    /** Status followed by the journal and any kept previous-build block. */
    public static String report(Context c) {
        StringBuilder out = new StringBuilder();
        out.append(status(c)).append('\n');
        out.append("-- journal (oldest first) --\n").append(journal(c));
        String marker = previousMarker(c);
        if (!marker.isEmpty()) {
            out.append("\n-- ").append(marker).append(" --\n").append(previousJournal(c));
        }
        return out.append('\n').toString();
    }

    // ---------------------------------------------------------------- internals

    private static boolean granted(Context c, String name) {
        if (Build.VERSION.SDK_INT < 23) return false;
        return c.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean unlocked(Context c) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    private static String version(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "unknown" : info.versionName;
            return name + " (" + code + ")";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The boot counter, so a kept line can be tied to the boot it came from. */
    static String bootId(Context c) {
        try {
            return String.valueOf(Settings.Global.getInt(c.getContentResolver(),
                    Settings.Global.BOOT_COUNT));
        } catch (Throwable t) {
            return DiagRotation.UNKNOWN;
        }
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

    /** Rotates before a read as well, so the page shows an update without an event. */
    private static void rotateIfNeeded(Context c) {
        try {
            File file = file(c);
            String version = version(c);
            String boot = bootId(c);
            synchronized (LOCK) {
                DiagRotation.maybeRotate(file.getParentFile(), version, boot);
            }
        } catch (Throwable t) {
            Log.w(Ensurer.TAG, "diag: cannot rotate journal: " + t);
        }
    }
}

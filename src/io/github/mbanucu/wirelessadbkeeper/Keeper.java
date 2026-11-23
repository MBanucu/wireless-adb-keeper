package io.github.mbanucu.wirelessadbkeeper;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
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
                ? fail(c, DiagReasons.Failure.WIFI_ON_NO_TERMUX,
                        "Termux is not installed or lacks RUN_COMMAND; turn Wi-Fi on by hand;"
                                + " fix: install Termux from F-Droid, set"
                                + " allow-external-apps=true, then "
                                + PermissionAudit.grantCommand(c.getPackageName(),
                                        PermissionAudit.RUN_COMMAND))
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
                return fail(c, DiagReasons.Failure.SERVICE_START_REFUSED,
                        "background start refused; fix: adb shell am start-foreground-service"
                                + " -n " + c.getPackageName() + "/.MonitorService");
            }
            return fail(c, DiagReasons.Failure.SERVICE_START_FAILED,
                    "could not start the monitor service: " + e);
        }
    }

    /** Asks Android to rejoin the saved network now. */
    public static String connectNow(Context c) {
        boolean auto = LocalWifi.isAutoMode(c);
        if (!auto && !LocalWifi.supported()) {
            return fail(c, DiagReasons.Failure.LOCAL_UNSUPPORTED,
                    "local-only mode needs Android 10 or newer");
        }
        if (!auto && !LocalWifi.hasLocationPermission(c)) {
            return fail(c, DiagReasons.Failure.LOCAL_NO_PERMISSION,
                    "location not granted; fix: "
                            + PermissionAudit.grantCommand(c.getPackageName(),
                                    PermissionAudit.FINE_LOCATION));
        }
        if (!sendServiceAction(c, MonitorService.ACTION_CONNECT_LOCAL)) {
            return fail(c, DiagReasons.Failure.LOCAL_SERVICE_REFUSED,
                    "monitor service is not running; start it first; fix:"
                            + " adb shell am start-foreground-service -n "
                            + c.getPackageName() + "/.MonitorService");
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
            return fail(c, DiagReasons.Failure.MODE_INVALID, "mode must be auto or local");
        }
        LocalWifi.setMode(c, auto ? LocalWifi.MODE_AUTO : LocalWifi.MODE_LOCAL);
        // Do not start the service: Android 12+ refuses that from the provider,
        // and a running monitor reloads from the revision key by itself.
        LocalWifi.bumpRevision(c);
        return "mode=" + (auto ? "auto-connect" : "local-only")
                + ", reload=" + reloadTarget();
    }

    /** Stores the credentials; the password is never echoed back. */
    public static String saveNetwork(Context c, String ssid, String passphrase) {
        if (ssid == null || ssid.trim().isEmpty()) {
            return fail(c, DiagReasons.Failure.SSID_MISSING, "ssid is required");
        }
        // The single-network command now saves by SSID, so an existing network
        // is updated instead of becoming a second profile. Security stays as
        // configured; this path has no security argument.
        boolean existed = Profiles.bySsid(c, ssid) != null;
        ProfileTable.Outcome saved = Profiles.save(c, ssid, null, passphrase);
        if (!saved.ok()) {
            return profileFailure(c, saved.error());
        }
        LocalWifi.bumpRevision(c);
        profileChanged(c, existed ? DiagReasons.ProfileChange.UPDATED
                : DiagReasons.ProfileChange.ADDED, saved.profile());
        String savedPass = Profiles.secret(c, saved.profile().id());
        return "ssid=" + saved.profile().ssid()
                + ", passphrase=" + (savedPass == null || savedPass.isEmpty() ? "none" : "set")
                + ", reload=" + reloadTarget();
    }

    /**
     * Saves an explicitly named profile, so a rename cannot create a second
     * profile for the same SSID. An unknown id is an error, not an implicit add:
     * {@code save SSID} is the command that creates.
     */
    public static String saveProfile(Context c, String id, String ssid, String passphrase) {
        String cleanId = id == null ? "" : id.trim();
        if (cleanId.isEmpty()) {
            return fail(c, DiagReasons.Failure.PROFILE_UNKNOWN,
                    DiagReasons.profileDetail(ProfileTable.ERROR_UNKNOWN));
        }
        if (ssid == null || ssid.trim().isEmpty()) {
            return fail(c, DiagReasons.Failure.SSID_MISSING, "ssid is required");
        }
        ProfileTable.Outcome saved = Profiles.update(c, cleanId, ssid, null, passphrase);
        if (!saved.ok()) {
            return profileFailure(c, saved.error());
        }
        LocalWifi.bumpRevision(c);
        profileChanged(c, DiagReasons.ProfileChange.UPDATED, saved.profile());
        String savedPass = Profiles.secret(c, saved.profile().id());
        return "id=" + saved.profile().id()
                + ", ssid=" + saved.profile().ssid()
                + ", passphrase=" + (savedPass == null || savedPass.isEmpty() ? "none" : "set")
                + ", reload=" + reloadTarget();
    }

    /**
     * One line per profile, in try order: id, SSID, security, whether a
     * passphrase is stored and the 1-based priority. The passphrase itself is
     * never read into the reply.
     */
    public static String profiles(Context c) {
        List<Profile> profiles = Profiles.list(c);
        if (profiles.isEmpty()) return "profiles=0";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            if (out.length() > 0) out.append('\n');
            out.append(ControlText.profile(profile, i + 1,
                    Profiles.hasSecret(c, profile.id())));
        }
        return out.toString();
    }

    /** Deletes a profile, its ciphertext and its applied-suggestion record. */
    public static String removeProfile(Context c, String id) {
        String cleanId = id == null ? "" : id.trim();
        if (cleanId.isEmpty()) {
            return fail(c, DiagReasons.Failure.PROFILE_UNKNOWN,
                    DiagReasons.profileDetail(ProfileTable.ERROR_UNKNOWN));
        }
        // Remove the platform suggestion while the applied record still names
        // it; ProfileTable.remove clears that record. On failure keep both so
        // the user can retry instead of leaving an untracked suggestion behind.
        if (!WifiSuggestion.removeOne(c, cleanId)) {
            String detail = "could not remove the suggestion for " + cleanId
                    + "; profile kept; retry the command";
            Diag.warn(c, "wifi.suggest.refused", detail);
            return "error=" + detail;
        }
        ProfileTable.Outcome removed = Profiles.remove(c, cleanId);
        if (!removed.ok()) {
            return profileFailure(c, removed.error());
        }
        LocalWifi.bumpRevision(c);
        profileChanged(c, DiagReasons.ProfileChange.REMOVED, removed.profile());
        return "removed=" + cleanId
                + ", ssid=" + removed.profile().ssid()
                + ", reload=" + reloadTarget();
    }

    /**
     * Moves a profile to a 1-based position in the try order. A position
     * outside the list is refused with the current range, so a typo does not
     * silently do nothing.
     */
    public static String setPriority(Context c, String id, String priorityText) {
        String cleanId = id == null ? "" : id.trim();
        List<Profile> before = Profiles.list(c);
        int from = priorityOf(before, cleanId);
        if (cleanId.isEmpty() || from < 0) {
            return fail(c, DiagReasons.Failure.PROFILE_UNKNOWN,
                    DiagReasons.profileDetail(ProfileTable.ERROR_UNKNOWN));
        }
        Integer priority = ControlText.priority(priorityText);
        if (priority == null || priority < 1 || priority > before.size()) {
            return fail(c, DiagReasons.Failure.PROFILE_PRIORITY,
                    "priority must be between 1 and " + before.size()
                            + "; fix: run ./keeper.sh profiles to see the order");
        }
        ProfileTable.Outcome moved = Profiles.move(c, cleanId, priority);
        if (!moved.ok()) {
            return profileFailure(c, moved.error());
        }
        if (from != priority.intValue()) {
            LocalWifi.bumpRevision(c);
            profileChanged(c, DiagReasons.ProfileChange.UPDATED, moved.profile());
        }
        return "id=" + moved.profile().id()
                + ", priority=" + priority
                + ", reload=" + reloadTarget();
    }

    /** Where a saved change takes effect: the live monitor, or its next start. */
    private static String reloadTarget() {
        return MonitorService.running ? "monitor" : "next start";
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
        boolean existed = new File(AdbIdentity.dir(c), AdbIdentity.KEY_FILE).isFile();
        try {
            AdbKey key = AdbIdentity.load(c);
            if (!existed) {
                Diag.info(c, "key.created", "no ADB key found; generated a new one;"
                        + " pair this app again");
            }
            return "pubkey=" + key.publicKeyBase64()
                    + "\nname=" + AdbIdentity.KEY_NAME
                    + "\npath=" + new File(AdbIdentity.dir(c), AdbIdentity.KEY_FILE);
        } catch (Exception e) {
            return fail(c, DiagReasons.Failure.KEY_LOAD_FAILED,
                    "could not load the ADB key: " + e.getMessage());
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
        List<Profile> profiles = Profiles.list(c);
        m.put("profiles", String.valueOf(profiles.size()));
        m.put("profile_active", profiles.isEmpty() ? "(none)" : profiles.get(0).id());
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

    /** One coded journal event, then the same sentence as the error reply. */
    private static String fail(Context c, DiagReasons.Failure failure, String detail) {
        return DiagReasons.fail(
                (level, code, reason) -> Diag.event(c, level, code, reason), failure, detail);
    }

    /** One coded journal event for a store refusal, with the same sentence. */
    private static String profileFailure(Context c, String error) {
        return fail(c, DiagReasons.profileFailure(error), DiagReasons.profileDetail(error));
    }

    /** Exactly one event per profile change; the detail never carries the secret. */
    private static void profileChanged(Context c, DiagReasons.ProfileChange change,
                                       Profile profile) {
        DiagReasons.changed(
                (level, code, reason) -> Diag.event(c, level, code, reason), change,
                "id=" + ControlText.oneLine(profile.id())
                        + " ssid=" + ControlText.oneLine(profile.ssid()));
    }

    /** The 1-based try position of an id, or -1 when it is not in the list. */
    private static int priorityOf(List<Profile> profiles, String id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(id)) return i + 1;
        }
        return -1;
    }
}

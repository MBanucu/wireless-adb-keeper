package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps a local-only Wi-Fi link to one SSID alive.
 *
 * The request drops NET_CAPABILITY_INTERNET on purpose: Android then treats the
 * network as local, so wlan0 stays up (and wireless debugging keeps listening)
 * while mobile data remains the default route. An access point that has no
 * internet can no longer capture all of the phone's traffic.
 *
 * Android requires the user to approve an app-initiated network request once per
 * request, so this class keeps the request registered for as long as possible and
 * only re-requests when the link is actually lost.
 */
public final class LocalWifi {

    public static final String DEFAULT_SSID = "";
    public static final String PREFS = "local_wifi";
    /** The pre-profiles key; migrated to a profile and never written again. */
    public static final String KEY_SSID = "ssid";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MODE = "mode";
    /** Bumped after a change the running monitor has to reload; see below. */
    public static final String KEY_REVISION = "revision";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_LOCAL = "local";
    public static final String PERM_LOCATION = "android.permission.ACCESS_FINE_LOCATION";

    private static final long REQUEST_TIMEOUT_MS = 90_000L;
    private static final long RECONNECT_DELAY_MS = 10_000L;

    /** Shown in the UI: off, disabled, requesting, connected, lost, paused, ... */
    public static volatile String state = "off";
    public static volatile String detail = "";
    /** Last local-only reason journalled; retries must not fill the journal. */
    private static String lastJournal = "";

    public interface Listener {
        void onConnected(String ssid);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private Network network;
    private Listener listener;
    private boolean requesting;
    private boolean started;
    private final Backoff backoff = new Backoff();
    private Runnable pending;

    public LocalWifi(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    public static String ssid(Context c) {
        Profile profile = Profiles.active(c);
        return profile == null ? DEFAULT_SSID : profile.ssid();
    }

    /**
     * The single-network spelling of a save: updates the profile with this SSID
     * (or appends one). New code should call {@link Profiles#save} directly so
     * the SSID and password land in one change.
     */
    public static void setSsid(Context c, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return;
        Profiles.save(c, clean, null, null);
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** The saved passphrase, or null when the Keystore cannot decrypt it. */
    public static String passphrase(Context c) {
        return Profiles.secret(c);
    }

    /**
     * Stores the passphrase encrypted; false means the Keystore failed. This is
     * the single-network spelling and targets the active profile; use
     * {@link Profiles#save} or {@link Profiles#update} for another one.
     */
    public static boolean setPassphrase(Context c, String value) {
        Profile profile = Profiles.active(c);
        return profile != null && Profiles.setSecret(c, profile.id(), value);
    }

    public static String mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_AUTO);
    }

    public static void setMode(Context c, String mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply();
    }

    /**
     * Marks the saved settings as changed for a monitor that is already running.
     *
     * A content-provider caller on Android 12+ cannot start the foreground
     * service, so it cannot deliver a reload intent. One counter key instead of
     * a listener on every setting also means saving an SSID and a passphrase
     * with two writes reloads once, after the last write.
     */
    public static void bumpRevision(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1L).apply();
    }

    public static boolean isAutoMode(Context c) {
        return MODE_AUTO.equals(mode(c));
    }

    public static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(PERM_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return network != null;
    }

    public void start() {
        if (started) return;
        started = true;
        lastJournal = "";
        backoff.reset();
        if (!enabled(context)) {
            state = "disabled";
            detail = "";
            return;
        }
        if (!supported()) {
            state = "unsupported";
            detail = "needs Android 10+";
            return;
        }
        request(0);
    }

    public void restart() {
        stop();
        start();
    }

    public void retryNow() {
        backoff.reset();
        if (!started) {
            start();
            return;
        }
        if (!enabled(context)) {
            state = "disabled";
            return;
        }
        request(0);
    }

    /** Called when Android reports any Wi-Fi network becoming available. */
    public void onWifiAvailable() {
        if (!started || network != null || requesting) return;
        if (backoff.exhausted()) return;
        backoff.clearGap();
        request(2_000L);
    }

    public void stop() {
        started = false;
        cancelPending();
        releaseCallback();
        network = null;
        requesting = false;
        state = "off";
        detail = "";
    }

    private void request(long delayMs) {
        if (!started) return;
        cancelPending();
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                connect();
            }
        };
        if (delayMs > 0) {
            handler.postDelayed(pending, delayMs);
        } else {
            handler.post(pending);
        }
    }

    private void connect() {
        if (!started || requesting) return;

        if (!enabled(context)) {
            state = "disabled";
            detail = "";
            return;
        }
        if (!hasLocationPermission(context)) {
            state = "no permission";
            detail = "fix: " + PermissionAudit.grantCommand(
                    context.getPackageName(), PERM_LOCATION);
            Log.w(Ensurer.TAG, "local wifi: missing " + PERM_LOCATION);
            journal("warn", "local.no_permission",
                    "location not granted; " + detail);
            return;
        }
        if (!wifiEnabled()) {
            state = "wifi off";
            detail = "";
            journal("warn", "local.wifi_off", "Wi-Fi radio is off; turn it on by hand or"
                    + " with the Turn Wi-Fi on button");
            return;
        }

        String target = ssid(context);
        if (network != null) {
            state = "connected";
            detail = target;
            return;
        }
        // A local-only request cannot match a network that the saved profile has
        // already connected with internet capability: the system searches forever.
        if (target.equals(currentSsid())) {
            state = "own network";
            detail = "saved network, turn off auto-connect";
            request(60_000L);
            return;
        }

        long wait = backoff.gapMs(SystemClock.elapsedRealtime());
        if (wait > 0) {
            request(wait);
            return;
        }

        String pass = passphrase(context);
        if (pass == null) {
            // Null means the Keystore could not decrypt a stored secret; an
            // absent secret is the empty string. Do not turn that failure into
            // an open-network request for a network that needs a password.
            state = "error";
            detail = "saved passphrase cannot be decrypted; save it again";
            journal("warn", "wifi.secret.undecryptable", detail);
            return;
        }
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            state = "error";
            detail = "no ConnectivityManager";
            journal("error", "local.no_service", "ConnectivityManager is unavailable");
            return;
        }

        WifiNetworkSpecifier.Builder specBuilder = new WifiNetworkSpecifier.Builder()
                .setSsid(target);
        if (pass != null && !pass.isEmpty()) {
            // Some APs advertise both wpa2-psk and wpa3-sae; alternate on retries.
            if (useWpa3) {
                specBuilder.setWpa3Passphrase(pass);
            } else {
                specBuilder.setWpa2Passphrase(pass);
            }
        }
        WifiNetworkSpecifier specifier = specBuilder.build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        final String security = (pass == null || pass.isEmpty())
                ? "open" : (useWpa3 ? "wpa3" : "wpa2");

        final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                if (callback != this) return;
                Diag.verbose(context, VerboseLog.NETWORK, "local link available: " + target);
                network = n;
                requesting = false;
                backoff.success();
                state = "connected";
                detail = target;
                Log.i(Ensurer.TAG, "local wifi connected: " + target);
                Log.i(Ensurer.TAG, "local wifi security used: " + security);
                NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    Log.w(Ensurer.TAG,
                            "local wifi: network still reports INTERNET capability");
                }
                if (listener != null) listener.onConnected(target);
            }

            @Override
            public void onLost(Network n) {
                if (callback != this) return;
                Diag.verbose(context, VerboseLog.NETWORK, "local link lost: " + target);
                network = null;
                requesting = false;
                state = "lost";
                detail = target;
                Log.i(Ensurer.TAG, "local wifi lost: " + target);
                releaseCallback();
                if (started && !backoff.exhausted()) {
                    request(RECONNECT_DELAY_MS);
                }
            }

            @Override
            public void onUnavailable() {
                if (callback != this) return;
                Diag.verbose(context, VerboseLog.NETWORK, "local link unavailable (attempt "
                        + backoff.attempts() + ", " + security + "): " + target);
                requesting = false;
                backoff.failed();
                useWpa3 = !useWpa3;
                releaseCallback();
                Log.w(Ensurer.TAG, "local wifi unavailable (attempt " + backoff.attempts()
                        + ", " + security + "): " + target);
                if (backoff.exhausted()) {
                    state = "paused";
                    detail = "approve in dialog or tap Connect now";
                    Log.w(Ensurer.TAG, "local wifi: pausing after " + backoff.attempts()
                            + " attempts");
                    journal("warn", "local.attempts_exhausted",
                            "no approval after " + backoff.attempts()
                                    + " attempts for " + target
                                    + "; approve in the dialog or tap Connect now");
                    return;
                }
                state = "requesting";
                detail = "waiting for approval";
                journal("warn", "local.dialog_timeout",
                        "the approval dialog timed out for " + target
                                + " (attempt " + backoff.attempts() + ")");
                request(backoff.retryDelayMs());
            }
        };

        requesting = true;
        backoff.attempted(SystemClock.elapsedRealtime());
        callback = cb;
        state = "requesting";
        detail = target;
        try {
            cm.requestNetwork(request, cb, (int) REQUEST_TIMEOUT_MS);
            Log.i(Ensurer.TAG, "local wifi: requested local-only link to " + target);
        } catch (SecurityException e) {
            requesting = false;
            releaseCallback();
            state = "error";
            detail = "permission denied";
            Log.e(Ensurer.TAG, "local wifi request denied", e);
            journal("error", "local.request_refused",
                    "the local-only request was refused: " + e);
        } catch (Exception e) {
            requesting = false;
            releaseCallback();
            state = "error";
            detail = String.valueOf(e.getMessage());
            Log.e(Ensurer.TAG, "local wifi request failed", e);
            journal("error", "local.request_failed",
                    "the local-only request failed for " + target + ": " + e);
        }
    }

    private boolean useWpa3 = true;

    private void releaseCallback() {
        ConnectivityManager.NetworkCallback old = callback;
        callback = null;
        if (old == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) manager.unregisterNetworkCallback(old);
        } catch (Exception ignored) {
        }
    }

    private void cancelPending() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
    }

    /** Journals a local-only reason once per distinct occurrence. */
    private void journal(String level, String code, String detail) {
        String key = level + " " + code + " " + detail;
        synchronized (LocalWifi.class) {
            if (key.equals(lastJournal)) return;
            lastJournal = key;
        }
        Diag.event(context, level, code, detail);
    }

    @SuppressWarnings("deprecation")
    private boolean wifiEnabled() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || "<unknown ssid>".equals(ssid)) return null;
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }
}

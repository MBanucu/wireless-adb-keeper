package io.github.mbanucu.wirelessadbkeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MonitorService extends Service {
    public static volatile boolean running = false;
    public static final String ACTION_CONNECT_LOCAL = "io.github.mbanucu.wirelessadbkeeper.CONNECT_LOCAL";
    public static final String ACTION_RELOAD_LOCAL = "io.github.mbanucu.wirelessadbkeeper.RELOAD_LOCAL";
    private static final String CHANNEL_ID = "monitor";
    private static final int NOTIFICATION_ID = 1;

    /**
     * 5555 is cheap to probe, so it is re-checked more often than the 15 min job.
     * 10 s keeps a port that closes with no network event inside #5's <= 30 s
     * target; Termux itself is still asked at most once per PinnedPort's 30 s
     * window, whatever this interval does.
     */
    private static final long PIN_CHECK_MS = 10_000L;

    private Handler handler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ContentObserver observer;
    private BroadcastReceiver unlockReceiver;
    private Runnable ensureRunnable;
    private Runnable pinTick;
    private Runnable pinRecheck;
    /** Whether the one-shot recheck is already queued; keeps ticks from stacking it. */
    private volatile boolean pinRecheckPending;
    private ExecutorService pinExecutor;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private String pendingReason = "";
    private LocalWifi localWifi;
    /** Whether the credential-protected Wi-Fi mode has been applied. */
    private boolean applied;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // First, so the journal reads start -> mode -> ensure in that order.
        Diag.info(this, "svc.start", "unlocked=" + userUnlocked());
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        // From targetSdk 34 the type has to be passed here as well as declared
        // in the manifest, or the start is refused outright.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        registerNetworkCallback();
        registerSettingObserver();
        registerUnlockReceiver();
        // The pin loop and Ensurer read no credential-protected app storage, so
        // they can run at LOCKED_BOOT_COMPLETED. The mode comes from credential-
        // protected prefs and has to wait for the first unlock.
        if (userUnlocked()) {
            registerPrefsListener();
            applyMode("service created");
        }
        scheduleEnsure("service created", 0);
        pinExecutor = Executors.newSingleThreadExecutor();
        pinRecheck = new Runnable() {
            @Override
            public void run() {
                pinRecheckPending = false;
                checkPin(true);
            }
        };
        startPinLoop(8000);
        Log.i(Ensurer.TAG, "MonitorService started"
                + (userUnlocked() ? "" : " (before unlock)"));
    }

    /** True once credential-encrypted storage and the Wi-Fi prefs are readable. */
    private boolean userUnlocked() {
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    private void registerUnlockReceiver() {
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(Ensurer.TAG, "user unlocked; applying saved Wi-Fi mode");
                Diag.info(MonitorService.this, "svc.unlocked", "applying saved mode");
                registerPrefsListener();
                applyMode("user unlocked");
                scheduleEnsure("user unlocked", 0);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(unlockReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "could not register unlock receiver", e);
        }
    }

    /**
     * Reloads the Wi-Fi mode when the saved settings change.
     *
     * This is the provider-safe path: `content call` can write SharedPreferences
     * but on Android 12+ cannot start this service, so a reload intent never
     * arrives. The provider bumps one revision key after its writes; watching
     * that key means a running monitor applies the change without a restart,
     * while a monitor that is not running reads the same prefs on its next
     * start. The prefs are credential-protected, so this waits for unlock.
     */
    private void registerPrefsListener() {
        if (prefsListener != null || !userUnlocked()) return;
        prefs = getSharedPreferences(LocalWifi.PREFS, Context.MODE_PRIVATE);
        prefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                if (!LocalWifi.KEY_REVISION.equals(key)) return;
                // The callback runs on whichever thread wrote the pref; a
                // `content call` writes on a binder thread. applyMode touches
                // LocalWifi and its network callbacks, which belong to the
                // service's main thread, so hand it over rather than race them.
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!running || !userUnlocked()) return;
                        Log.i(Ensurer.TAG, "saved Wi-Fi settings changed; reloading");
                        applyMode("settings saved");
                    }
                });
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void applyMode(String reason) {
        applied = true;
        if (LocalWifi.isAutoMode(this)) {
            if (localWifi != null) {
                localWifi.stop();
                localWifi = null;
            }
            int status = WifiSuggestion.add(this);
            LocalWifi.state = "auto";
            LocalWifi.detail = suggestionDetail(status);
            Log.i(Ensurer.TAG, "mode auto (" + reason + "): " + LocalWifi.detail);
            Diag.info(this, "svc.mode", reason + ": auto-connect, " + LocalWifi.detail
                    + " (status " + status + ")");
        } else {
            WifiSuggestion.remove(this);
            if (localWifi == null) {
                startLocalWifi();
            } else {
                localWifi.restart();
            }
            Log.i(Ensurer.TAG, "mode local-only (" + reason + ")");
            Diag.info(this, "svc.mode", reason + ": local-only");
        }
        updateNotification();
    }

    private void startLocalWifi() {
        localWifi = new LocalWifi(this);
        localWifi.setListener(new LocalWifi.Listener() {
            @Override
            public void onConnected(String ssid) {
                scheduleEnsure("local wifi " + ssid, 300);
                updateNotification();
            }
        });
        localWifi.start();
    }

    private static String suggestionDetail(int status) {
        if (status == WifiSuggestion.STATUS_SUCCESS) return "suggestion active";
        if (status == -1) return "no Wi-Fi service";
        if (status == -2) return "enter Wi-Fi password";
        if (status == -3) return "could not build suggestion";
        if (status == -4) return "suggestion request failed";
        return "suggestion status " + status;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "Wireless ADB monitor", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Keeps wireless debugging enabled");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Wireless ADB Keeper")
                .setContentText(notificationText())
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private String notificationText() {
        String base = "Keeping wireless debugging enabled";
        // The mode lives in credential-protected prefs; before unlock the
        // notification must not read them.
        if (userUnlocked() && LocalWifi.isAutoMode(this)) {
            return base + " - auto-connect via suggestion";
        }
        if (localWifi == null) return base;
        String st = LocalWifi.state;
        if ("connected".equals(st)) {
            return base + " - " + LocalWifi.detail + " link is local-only";
        }
        if ("requesting".equals(st)) {
            return base + " - requesting local-only link to " + LocalWifi.detail;
        }
        return base;
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleEnsure("wifi available", 500);
                // A new network is when adbd comes back with a fresh random
                // port, and when wireless debugging has to be switched on
                // again before anything can be pinned.
                startPinLoop(15000);
                if (localWifi != null) localWifi.onWifiAvailable();
            }
        };
        try {
            cm.registerNetworkCallback(req, networkCallback);
            Log.i(Ensurer.TAG, "network callback registered");
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "registerNetworkCallback failed", e);
        }
    }

    private void registerSettingObserver() {
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (selfChange) return;
                if (android.os.SystemClock.elapsedRealtime() < Ensurer.quietUntil) return;
                if (!Ensurer.isWirelessDebuggingOn(MonitorService.this)
                        && Ensurer.isWifiConnected(MonitorService.this)) {
                    scheduleEnsure("setting turned off", 500);
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Ensurer.KEY_ADB_WIFI), false, observer);
    }

    private void scheduleEnsure(String reason, long delayMs) {
        pendingReason = reason;
        if (ensureRunnable != null) {
            handler.removeCallbacks(ensureRunnable);
        }
        ensureRunnable = new Runnable() {
            @Override
            public void run() {
                ensureRunnable = null;
                String result = Ensurer.ensure(MonitorService.this, pendingReason);
                Log.i(Ensurer.TAG, "monitor ensure (" + pendingReason + "): " + result);
                if (result.startsWith("Enabled")) {
                    // adbd restarts with the new setting; give it time to listen
                    // before asking for the fixed port.
                    startPinLoop(15000);
                }
            }
        };
        handler.postDelayed(ensureRunnable, delayMs);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        boolean unlocked = userUnlocked();
        if (ACTION_RELOAD_LOCAL.equals(action) && unlocked) {
            applyMode("settings changed");
        } else if (ACTION_CONNECT_LOCAL.equals(action) && unlocked) {
            if (LocalWifi.isAutoMode(this)) {
                int status = WifiSuggestion.add(this);
                LocalWifi.state = "auto";
                LocalWifi.detail = suggestionDetail(status);
                Log.i(Ensurer.TAG, "mode auto (connect now): " + LocalWifi.detail);
                Diag.info(this, "svc.connect", "auto-connect, " + LocalWifi.detail
                        + " (status " + status + ")");
                updateNotification();
            } else if (localWifi != null) {
                localWifi.retryNow();
                updateNotification();
            }
        } else if (unlocked && localWifi != null && "no permission".equals(LocalWifi.state)
                && LocalWifi.hasLocationPermission(this)) {
            // Permission was granted after the service started.
            localWifi.retryNow();
            updateNotification();
        }
        // BOOT_COMPLETED starts the service again after unlock, which is the
        // fallback if the ACTION_USER_UNLOCKED receiver was missed.
        if (unlocked && !applied) {
            applyMode("start command");
        }
        scheduleEnsure("start command", 0);
        return START_STICKY;
    }

    /**
     * Runs a pin attempt now (or soon) and keeps re-checking the fixed port while
     * the service lives. The port itself is probed and repaired by PinnedPort;
     * this only decides how often it is asked to look.
     *
     * Timing stays on the main handler and the probe runs on one worker thread.
     * A fresh Thread per tick with a 15 s sleep could outlive onDestroy; here
     * the recheck is just another delayed message and onDestroy removes both.
     */
    private void startPinLoop(long delayMs) {
        if (pinTick != null) {
            handler.removeCallbacks(pinTick);
        }
        // A network or ensure event makes a pending recheck redundant.
        handler.removeCallbacks(pinRecheck);
        pinRecheckPending = false;
        pinTick = new Runnable() {
            @Override
            public void run() {
                checkPin(false);
                handler.postDelayed(this, PIN_CHECK_MS);
            }
        };
        handler.postDelayed(pinTick, delayMs);
    }

    /** Probes the fixed port on the single pin worker, never on the main thread. */
    private void checkPin(final boolean recheck) {
        final ExecutorService executor = pinExecutor;
        if (executor == null) return;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!running) return;
                    String state = PinnedPort.check(MonitorService.this);
                    // onDestroy may have run while the probe was in flight.
                    if (!running) return;
                    Log.i(Ensurer.TAG, "pin check: " + state);
                    if (!recheck && !state.startsWith("listening") && !pinRecheckPending) {
                        // The Termux script needs a few seconds; probe again
                        // before the next tick, so a pin that worked is not
                        // reported as closed for a whole interval. PinnedPort's
                        // quiet window keeps the recheck from asking twice.
                        pinRecheckPending = true;
                        handler.postDelayed(pinRecheck, 15_000L);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // onDestroy shut the worker down.
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (pinTick != null) {
            handler.removeCallbacks(pinTick);
            pinTick = null;
        }
        if (pinRecheck != null) {
            handler.removeCallbacks(pinRecheck);
            pinRecheckPending = false;
        }
        if (pinExecutor != null) {
            // Interrupts a scheduled task and refuses later submissions; an
            // in-flight probe is bounded by its 400 ms socket timeout.
            pinExecutor.shutdownNow();
            pinExecutor = null;
        }
        if (localWifi != null) {
            localWifi.stop();
            localWifi = null;
        }
        if (ensureRunnable != null) {
            handler.removeCallbacks(ensureRunnable);
            ensureRunnable = null;
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
        }
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {
            }
            unlockReceiver = null;
        }
        if (prefs != null && prefsListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            prefsListener = null;
            prefs = null;
        }
        Log.i(Ensurer.TAG, "MonitorService stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

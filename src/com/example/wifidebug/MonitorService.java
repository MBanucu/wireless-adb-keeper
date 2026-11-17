package com.example.wifidebug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.provider.Settings;
import android.util.Log;

public class MonitorService extends Service {
    public static volatile boolean running = false;
    public static final String ACTION_CONNECT_LOCAL = "com.example.wifidebug.CONNECT_LOCAL";
    public static final String ACTION_RELOAD_LOCAL = "com.example.wifidebug.RELOAD_LOCAL";
    private static final String CHANNEL_ID = "monitor";
    private static final int NOTIFICATION_ID = 1;

    private Handler handler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ContentObserver observer;
    private Runnable ensureRunnable;
    private String pendingReason = "";
    private LocalWifi localWifi;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerNetworkCallback();
        registerSettingObserver();
        applyMode("service created");
        scheduleEnsure("service created", 0);
        Log.i(Ensurer.TAG, "MonitorService started");
    }

    private void applyMode(String reason) {
        if (LocalWifi.isAutoMode(this)) {
            if (localWifi != null) {
                localWifi.stop();
                localWifi = null;
            }
            int status = WifiSuggestion.add(this);
            LocalWifi.state = "auto";
            LocalWifi.detail = suggestionDetail(status);
            Log.i(Ensurer.TAG, "mode auto (" + reason + "): " + LocalWifi.detail);
        } else {
            WifiSuggestion.remove(this);
            if (localWifi == null) {
                startLocalWifi();
            } else {
                localWifi.restart();
            }
            Log.i(Ensurer.TAG, "mode local-only (" + reason + ")");
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
        if (status == -2) return "enter Wi-Fi password";
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
        if (LocalWifi.isAutoMode(this)) {
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
                String result = Ensurer.ensure(MonitorService.this);
                Log.i(Ensurer.TAG, "monitor ensure (" + pendingReason + "): " + result);
            }
        };
        handler.postDelayed(ensureRunnable, delayMs);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RELOAD_LOCAL.equals(action)) {
            applyMode("settings changed");
        } else if (ACTION_CONNECT_LOCAL.equals(action)) {
            if (LocalWifi.isAutoMode(this)) {
                int status = WifiSuggestion.add(this);
                LocalWifi.state = "auto";
                LocalWifi.detail = suggestionDetail(status);
                Log.i(Ensurer.TAG, "mode auto (connect now): " + LocalWifi.detail);
                Log.i(Ensurer.TAG, "mode auto (connect now): " + LocalWifi.detail);
                updateNotification();
            } else if (localWifi != null) {
                localWifi.retryNow();
                updateNotification();
            }
        } else if (localWifi != null && "no permission".equals(LocalWifi.state)
                && LocalWifi.hasLocationPermission(this)) {
            // Permission was granted after the service started.
            localWifi.retryNow();
            updateNotification();
        }
        scheduleEnsure("start command", 0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
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
        Log.i(Ensurer.TAG, "MonitorService stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

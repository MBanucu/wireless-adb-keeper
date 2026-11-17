package com.example.wifidebug;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private Button serviceButton;
    private Button localButton;
    private EditText ssidField;
    private EditText passField;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean reEnabling = false;
    private ContentObserver observer;
    private Runnable statusTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        startMonitorService();
        scheduleJobs(this);
        requestNotificationPermission();
        requestLocationPermission();
        ensureAndRefresh();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Wireless ADB Keeper");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Keeps Android wireless debugging enabled whenever Wi-Fi is connected, "
                + "and reconnects to your chosen network automatically.");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(button("Enable now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureAndRefresh();
            }
        }));

        serviceButton = button("Stop monitor service", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MonitorService.running) {
                    stopService(new Intent(MainActivity.this, MonitorService.class));
                    toast("Monitor service stopped");
                } else {
                    startMonitorService();
                    toast("Monitor service started");
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                }, 500);
            }
        });
        root.addView(serviceButton);

        localButton = button("Connect mode: auto-connect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean toLocal = LocalWifi.isAutoMode(MainActivity.this);
                LocalWifi.setMode(MainActivity.this,
                        toLocal ? LocalWifi.MODE_LOCAL : LocalWifi.MODE_AUTO);
                sendServiceAction(MonitorService.ACTION_RELOAD_LOCAL);
                toast(toLocal
                        ? "Local-only mode: needs a tap + password each reconnect"
                        : "Auto-connect mode: system reconnects silently");
                refreshStatus();
            }
        });
        root.addView(localButton);

        root.addView(button("Connect now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!LocalWifi.isAutoMode(MainActivity.this) && !LocalWifi.supported()) {
                    toast("Needs Android 10 or newer");
                    return;
                }
                if (!LocalWifi.hasLocationPermission(MainActivity.this) && !LocalWifi.isAutoMode(MainActivity.this)) {
                    requestLocationPermission();
                    toast("Grant Location, then try again");
                    return;
                }
                sendServiceAction(MonitorService.ACTION_CONNECT_LOCAL);
                toast(LocalWifi.isAutoMode(MainActivity.this)
                        ? "Suggestion added - approve the Wi-Fi notification once"
                        : "Requesting local-only link");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                }, 1500);
            }
        }));

        ssidField = new EditText(this);
        ssidField.setHint("SSID for the local-only link");
        ssidField.setText(LocalWifi.ssid(this));
        LinearLayout.LayoutParams ssidLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ssidLp.topMargin = dp(12);
        ssidField.setLayoutParams(ssidLp);
        root.addView(ssidField);

        passField = new EditText(this);
        passField.setHint("Wi-Fi password (needed only if the app must connect it)");
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setText(LocalWifi.passphrase(this));
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        passLp.topMargin = dp(8);
        passField.setLayoutParams(passLp);
        root.addView(passField);

        root.addView(button("Save SSID and password", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LocalWifi.setSsid(MainActivity.this, ssidField.getText().toString());
                LocalWifi.setPassphrase(MainActivity.this, passField.getText().toString());
                sendServiceAction(MonitorService.ACTION_RELOAD_LOCAL);
                ssidField.setText(LocalWifi.ssid(MainActivity.this));
                toast("Saved: " + LocalWifi.ssid(MainActivity.this));
                refreshStatus();
            }
        }));

        root.addView(button("Schedule keep-alive (every 15 min)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scheduleJobs(MainActivity.this);
                toast("Keep-alive scheduled");
                refreshStatus();
            }
        }));

        root.addView(button("Open Developer options", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    toast("Could not open settings: " + e);
                }
            }
        }));

        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setPadding(0, dp(20), 0, 0);
        help.setTextIsSelectable(true);
        help.setText("One-time setup (already done if the status shows permission granted):\n\n"
                + "adb shell pm grant com.example.wifidebug android.permission.WRITE_SECURE_SETTINGS\n\n"
                + "How it works:\n"
                + "- A foreground service watches for new Wi-Fi connections and re-enables wireless debugging immediately.\n"
                + "- It also notices if wireless debugging is turned off while Wi-Fi is connected.\n"
                + "- A periodic job and boot receiver re-apply the setting as a fallback.\n"
                + "- Auto-connect mode registers a Wi-Fi suggestion: approve the system notification once, then Android "
                + "reconnects silently, even after reboots. It is marked metered so mobile data stays the default route.\n"
                + "- Local-only mode instead holds the network as a local link that can never carry internet, but "
                + "Android makes you approve a dialog and needs the password on every reconnect.\n"
                + "- Only devices already paired with this phone can connect. "
                + "Revoke pairings in Developer options if needed.");
        root.addView(help);

        return scroll;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            toast("Could not start monitor service: " + e);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }
    }

    private void requestLocationPermission() {
        if (!LocalWifi.supported()) return;
        if (!LocalWifi.hasLocationPermission(this)) {
            requestPermissions(new String[]{LocalWifi.PERM_LOCATION}, 2);
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            toast("Could not reach monitor service: " + e);
        }
    }

    private void ensureAndRefresh() {
        final Context ctx = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = Ensurer.ensure(ctx);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        toast(result);
                    }
                });
            }
        }).start();
    }

    private void refreshStatus() {
        if (status == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Permission:  ").append(Ensurer.hasPermission(this) ? "granted" : "NOT granted").append('\n');
        if (LocalWifi.supported()) {
            sb.append("Location:    ").append(LocalWifi.hasLocationPermission(this)
                    ? "granted" : "NOT granted").append('\n');
        }
        sb.append("Wi-Fi:       ").append(Ensurer.isWifiConnected(this) ? "connected" : "not connected").append('\n');
        sb.append("Wireless:    ").append(Ensurer.isWirelessDebuggingOn(this) ? "ON" : "OFF").append('\n');
        sb.append("Service:     ").append(MonitorService.running ? "running" : "stopped").append('\n');
        if (LocalWifi.isAutoMode(this)) {
            sb.append("Connect:     auto-connect (suggestion)");
            if (MonitorService.running && LocalWifi.detail != null && !LocalWifi.detail.isEmpty()) {
                sb.append(" (").append(LocalWifi.detail).append(')');
            }
            sb.append('\n');
        } else {
            sb.append("Connect:     local-only\n");
            sb.append("Local link:  ").append(MonitorService.running ? LocalWifi.state : "off");
            if (MonitorService.running && LocalWifi.detail != null && !LocalWifi.detail.isEmpty()) {
                sb.append(" (").append(LocalWifi.detail).append(')');
            }
            sb.append('\n');
        }
        sb.append("Job:         ").append(isJobScheduled() ? "scheduled" : "not scheduled");
        status.setText(sb.toString());
        if (serviceButton != null) {
            serviceButton.setText(MonitorService.running ? "Stop monitor service" : "Start monitor service");
        }
        if (localButton != null) {
            localButton.setText("Connect mode: "
                    + (LocalWifi.isAutoMode(this) ? "auto-connect" : "local-only"));
        }
    }

    private boolean isJobScheduled() {
        JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return false;
        for (JobInfo info : js.getAllPendingJobs()) {
            if (info.getId() == BootReceiver.PERIODIC_JOB) return true;
        }
        return false;
    }

    public static void scheduleJobs(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            JobInfo periodic = new JobInfo.Builder(BootReceiver.PERIODIC_JOB,
                    new ComponentName(context, EnsureJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            js.schedule(periodic);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                refreshStatus();
                if (selfChange || reEnabling) return;
                if (android.os.SystemClock.elapsedRealtime() < Ensurer.quietUntil) return;
                if (!Ensurer.isWirelessDebuggingOn(MainActivity.this)
                        && Ensurer.isWifiConnected(MainActivity.this)
                        && Ensurer.hasPermission(MainActivity.this)) {
                    reEnabling = true;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Ensurer.ensure(MainActivity.this);
                            reEnabling = false;
                            refreshStatus();
                        }
                    }, 800);
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Ensurer.KEY_ADB_WIFI), false, observer);
        refreshStatus();
        statusTick = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(statusTick, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (statusTick != null) {
            handler.removeCallbacks(statusTick);
            statusTick = null;
        }
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
    }
}

package com.example.wifidebug;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    // A small fixed palette so the screen reads the same on every device theme.
    private static final int ACCENT = 0xFF3B5BDB;
    private static final int TEXT = 0xFF111827;
    private static final int MUTED = 0xFF6B7280;
    private static final int OK = 0xFF15803D;
    private static final int BAD = 0xFFB91C1C;
    private static final int CARD_BG = 0xFFF3F4F6;
    private static final int HAIRLINE = 0xFFD1D5DB;

    private static final String[] PAGE_TITLES = {"Status", "Connect", "Network", "Help"};
    private static final String STATE_PAGE = "page";

    private LinearLayout statusBox;
    private Button serviceButton;
    private Button localButton;
    private EditText ssidField;
    private EditText passField;
    private View[] pages;
    private View[] navIndicators;
    private TextView[] navLabels;
    private int page = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean reEnabling = false;
    private ContentObserver observer;
    private Runnable statusTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No platform action bar: the window is edge to edge, so we keep our own
        // header and pad it past the status bar.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildUi());
        // Android 15 ignores status bar colours, so ask for dark icons instead:
        // the page background is light all the way to the top. This needs the
        // decor view, which only exists once the content is set.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (savedInstanceState != null) showPage(savedInstanceState.getInt(STATE_PAGE, 0));
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
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                return insets;
            }
        });

        FrameLayout content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        pages = new View[] {pageStatus(), pageConnect(), pageNetwork(), pageHelp()};
        for (View p : pages) {
            p.setVisibility(View.GONE);
            content.addView(p, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        shell.addView(navBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        showPage(0);
        return shell;
    }

    private LinearLayout column() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        return root;
    }

    private ScrollView scrollOf(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private void pageHeader(LinearLayout root, String title, String caption) {
        TextView overline = text("WIRELESS ADB KEEPER", 10, MUTED);
        overline.setTypeface(Typeface.DEFAULT_BOLD);
        overline.setLetterSpacing(0.12f);
        root.addView(overline);

        TextView t = text(title, 24, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(spaced(matchWrap(), 2));
        root.addView(t);
        TextView c = text(caption, 14, MUTED);
        c.setLineSpacing(dp(2), 1f);
        c.setLayoutParams(spaced(matchWrap(), 4));
        root.addView(c);
    }

    private View pageStatus() {
        LinearLayout root = column();
        pageHeader(root, "Status", "Live state of the keeper. Green means healthy.");

        root.addView(sectionLabel("Overview"));
        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setBackground(rounded(CARD_BG, dp(14), 0, 0));
        statusBox.setPadding(dp(16), dp(6), dp(16), dp(6));
        root.addView(statusBox, matchWrap());

        root.addView(sectionLabel("Actions"));
        root.addView(primary("Enable now", new View.OnClickListener() {
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
        return scrollOf(root);
    }

    private View pageConnect() {
        LinearLayout root = column();
        pageHeader(root, "Connect",
                "Choose how this phone rejoins Wi-Fi and keep the adb port stable.");

        root.addView(sectionLabel("Reconnect"));
        root.addView(primary("Connect now", new View.OnClickListener() {
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

        root.addView(sectionLabel("ADB port"));
        root.addView(button("Pin adb port " + PinnedPort.PORT + " now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String state = PinnedPort.force(MainActivity.this);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("Pinned port: " + state);
                                refreshStatus();
                            }
                        });
                    }
                }).start();
            }
        }));

        return scrollOf(root);
    }

    private View pageNetwork() {
        LinearLayout root = column();
        pageHeader(root, "Network",
                "Credentials the app needs when Android cannot reconnect on its own.");

        root.addView(sectionLabel("Saved network"));
        ssidField = field("SSID", InputType.TYPE_CLASS_TEXT);
        ssidField.setText(LocalWifi.ssid(this));
        ssidField.setLayoutParams(spaced(matchWrap(), 10));
        root.addView(ssidField);

        passField = field("Password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setText(LocalWifi.passphrase(this));
        passField.setLayoutParams(spaced(matchWrap(), 10));
        root.addView(passField);

        TextView passHint = text("Only used in local-only mode, where Android asks for the "
                + "password on every reconnect.", 12, MUTED);
        passHint.setLayoutParams(spaced(matchWrap(), 6));
        passHint.setLineSpacing(dp(2), 1f);
        root.addView(passHint);

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
        return scrollOf(root);
    }

    private View pageHelp() {
        LinearLayout root = column();
        pageHeader(root, "Help", "One-time setup, background behaviour, and options.");

        root.addView(sectionLabel("Keep-alive"));
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

        root.addView(sectionLabel("How it works"));
        TextView help = text("One-time setup (already done if the status shows permission granted):\n\n"
                + "adb shell pm grant com.example.wifidebug android.permission.WRITE_SECURE_SETTINGS\n\n"
                + "How it works:\n"
                + "- A foreground service watches for new Wi-Fi connections and re-enables wireless debugging immediately.\n"
                + "- It also notices if wireless debugging is turned off while Wi-Fi is connected.\n"
                + "- A periodic job and boot receiver re-apply the setting as a fallback.\n"
                + "- Auto-connect mode registers a Wi-Fi suggestion: approve the system notification once, then Android "
                + "reconnects silently, even after reboots. It is marked metered so mobile data stays the default route.\n"
                + "- Local-only mode instead holds the network as a local link that can never carry internet, but "
                + "Android makes you approve a dialog and needs the password on every reconnect.\n"
                + "- The fixed port 5555 is kept by Termux's own adb, which is already paired with this "
                + "phone: when the port is closed the app asks Termux to run "
                + "wireless-adb-keeper/pin-adb-port.sh, which sends 'adb tcpip 5555'. An app cannot "
                + "send that command itself. One-time grant: pm grant com.example.wifidebug "
                + "com.termux.permission.RUN_COMMAND\n"
                + "- Only devices already paired with this phone can connect. "
                + "Revoke pairings in Developer options if needed.", 12, MUTED);
        help.setTextIsSelectable(true);
        help.setLineSpacing(dp(3), 1f);
        root.addView(help);
        return scrollOf(root);
    }

    private View navBar() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(0xFFFBFBFD);

        View hairline = new View(this);
        hairline.setBackgroundColor(HAIRLINE);
        wrapper.addView(hairline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        // targetSdk 35 draws edge to edge, so keep the bar above the system gesture area.
        wrapper.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.addView(bar, matchWrap());

        navIndicators = new View[PAGE_TITLES.length];
        navLabels = new TextView[PAGE_TITLES.length];
        for (int i = 0; i < PAGE_TITLES.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(9), 0, dp(8));
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            View indicator = new View(this);
            indicator.setBackground(rounded(ACCENT, dp(2), 0, 0));
            indicator.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(3));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(indicator, ilp);

            TextView label = text(PAGE_TITLES[i], 12, MUTED);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(7), 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(index);
                }
            });
            bar.addView(item);
            navIndicators[i] = indicator;
            navLabels[i] = label;
        }
        return wrapper;
    }

    private void showPage(int index) {
        if (pages == null || index < 0 || index >= pages.length) return;
        page = index;
        for (int i = 0; i < pages.length; i++) {
            boolean active = i == index;
            pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            if (navIndicators != null) {
                navIndicators[i].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            }
            if (navLabels != null) {
                navLabels[i].setTextColor(active ? ACCENT : MUTED);
                navLabels[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, page);
    }

    // Back returns to Status first; only then does it leave the app.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (page != 0) {
            showPage(0);
            return;
        }
        super.onBackPressed();
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView sectionLabel(String label) {
        TextView t = text(label.toUpperCase(), 11, MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable rounded(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(LinearLayout.LayoutParams lp, int topDp) {
        lp.topMargin = dp(topDp);
        return lp;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackgroundTintList(ColorStateList.valueOf(HAIRLINE));
        e.setLayoutParams(matchWrap());
        return e;
    }

    // One filled button for the thing people came to do, flat ones for the rest.
    private Button primary(String label, View.OnClickListener listener) {
        Button b = button(label, listener);
        b.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(TEXT);
        b.setMinHeight(dp(46));
        b.setBackgroundTintList(ColorStateList.valueOf(0xFFE5E7EB));
        b.setOnClickListener(listener);
        b.setLayoutParams(spaced(matchWrap(), 8));
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
        if (statusBox == null) return;
        statusBox.removeAllViews();

        boolean permission = Ensurer.hasPermission(this);
        addRow("Permission", permission ? "granted" : "not granted", permission ? OK : BAD);
        if (LocalWifi.supported()) {
            boolean location = LocalWifi.hasLocationPermission(this);
            addRow("Location", location ? "granted" : "not granted", location ? OK : BAD);
        }
        boolean wifi = Ensurer.isWifiConnected(this);
        addRow("Wi-Fi", wifi ? "connected" : "not connected", wifi ? OK : null);
        boolean wireless = Ensurer.isWirelessDebuggingOn(this);
        addRow("Wireless", wireless ? "on" : "off", wireless ? OK : BAD);
        boolean running = MonitorService.running;
        addRow("Service", running ? "running" : "stopped", running ? OK : BAD);
        addRow("Pinned port", PinnedPort.state());
        addRow("Mode", LocalWifi.isAutoMode(this) ? "auto-connect" : "local-only");
        if (!LocalWifi.isAutoMode(this)) {
            addRow("Local link", running ? LocalWifi.state : "off");
        }
        if (running && LocalWifi.detail != null && !LocalWifi.detail.isEmpty()) {
            addRow("Last result", LocalWifi.detail);
        }
        addRow("Job", isJobScheduled() ? "scheduled" : "not scheduled");

        if (serviceButton != null) {
            serviceButton.setText(MonitorService.running ? "Stop monitor service" : "Start monitor service");
        }
        if (localButton != null) {
            localButton.setText("Connect mode: "
                    + (LocalWifi.isAutoMode(this) ? "auto-connect" : "local-only"));
        }
    }

    private void addRow(String label, String value) {
        addRow(label, value, null);
    }

    // A label column of fixed width keeps every value lined up without monospace.
    private void addRow(String label, String value, Integer color) {
        if (statusBox == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView name = text(label, 13, MUTED);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(name);

        TextView val = text(value, 14, color == null ? TEXT : color);
        val.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val);

        statusBox.addView(row, matchWrap());
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

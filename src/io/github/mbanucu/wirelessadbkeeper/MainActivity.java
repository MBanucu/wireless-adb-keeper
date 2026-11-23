package io.github.mbanucu.wirelessadbkeeper;

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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
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

    private static final String[] PAGE_TITLES = {"Status", "Connect", "Network", "Diag", "Help"};
    /** Index of the diagnostics page; the Help page keeps the last slot. */
    private static final int PAGE_DIAG = 3;
    private static final String STATE_PAGE = "page";

    private LinearLayout statusBox;
    private TextView diagStatus;
    private TextView diagJournal;
    private Switch verboseToggle;
    private TextView verboseNote;
    private CompoundButton.OnCheckedChangeListener verboseListener;
    private RadioGroup retentionGroup;
    private RadioGroup.OnCheckedChangeListener retentionListener;
    private TextView diagPreviousNote;
    private TextView diagPrevious;
    private Button serviceButton;
    private Button localButton;
    private EditText ssidField;
    private EditText passField;
    private View[] pages;
    private View[] navIndicators;
    private TextView[] navLabels;
    private int page = 0;
    private String lastStatusKey = "";
    private final java.util.Set<String> openHints = new java.util.HashSet<>();
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

        pages = new View[] {pageStatus(), pageConnect(), pageNetwork(), pageDiagnostics(), pageHelp()};
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
        action(root, "Enable now",
                "Re-applies wireless debugging right away when Wi-Fi is connected. Safe to "
                        + "press at any time; it only sets the switch, it changes no other setting.",
                true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ensureAndRefresh();
                    }
                });

        action(root, "Turn Wi-Fi on",
                "Android 10+ will not let an app switch Wi-Fi itself, so this asks Termux's "
                        + "adb to run `svc wifi enable`. If Termux is missing or not trusted, the "
                        + "Wi-Fi panel opens for a tap instead.",
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        turnWifiOn();
                    }
                });

        serviceButton = action(root, "Stop monitor service",
                "Stops or restarts the foreground service that watches Wi-Fi and keeps "
                        + "wireless debugging on. The 15-minute fallback job stays scheduled.",
                false, new View.OnClickListener() {
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
        return scrollOf(root);
    }

    private View pageConnect() {
        LinearLayout root = column();
        pageHeader(root, "Connect",
                "Choose how this phone rejoins Wi-Fi and keep the adb port stable.");

        root.addView(sectionLabel("Reconnect"));
        action(root, "Connect now",
                "Asks Android to join the saved network now: a Wi-Fi suggestion in auto mode, "
                        + "or a local link in local-only mode. In auto mode approve the system "
                        + "notification once and later reconnects happen silently.",
                true, new View.OnClickListener() {
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
        });

        localButton = action(root, "Connect mode: auto-connect",
                "Switches how the phone rejoins the network. auto-connect registers a Wi-Fi "
                        + "suggestion Android applies silently, even after reboot, using the saved "
                        + "password. local-only holds a local link that can never carry internet, but "
                        + "needs a tap on every reconnect.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean toLocal = LocalWifi.isAutoMode(MainActivity.this);
                LocalWifi.setMode(MainActivity.this,
                        toLocal ? LocalWifi.MODE_LOCAL : LocalWifi.MODE_AUTO);
                sendServiceAction(MonitorService.ACTION_RELOAD_LOCAL);
                toast(toLocal
                        ? "Local-only mode: needs a tap each reconnect"
                        : "Auto-connect mode: system reconnects silently");
                refreshStatus();
            }
        });

        root.addView(sectionLabel("ADB port"));
        action(root, "Pin adb port " + PinnedPort.PORT + " now",
                "Sends `adb tcpip 5555` through Termux right now, so adbd listens on the fixed "
                        + "port even after it restarts. The monitor service otherwise does this "
                        + "automatically when the port closes.",
                false, new View.OnClickListener() {
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
        });

        return scrollOf(root);
    }

    private View pageNetwork() {
        LinearLayout root = column();
        pageHeader(root, "Network",
                "The Wi-Fi network to rejoin, and the password it uses.");

        root.addView(sectionLabel("Saved network"));
        ssidField = field("SSID", InputType.TYPE_CLASS_TEXT);
        ssidField.setText(LocalWifi.ssid(this));
        ssidField.setLayoutParams(spaced(matchWrap(), 10));
        root.addView(ssidField);

        // Never write the saved passphrase back into the field: a screenshot or
        // a glance is enough to leak it. The hint only says whether one exists.
        passField = field(passphraseHint(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setLayoutParams(spaced(matchWrap(), 10));
        root.addView(passField);

        TextView passHint = text("Used in both modes. Auto-connect registers the network with "
                + "the saved password; open networks need none.", 12, MUTED);
        passHint.setLayoutParams(spaced(matchWrap(), 6));
        passHint.setLineSpacing(dp(2), 1f);
        root.addView(passHint);

        action(root, "Save SSID and password",
                "Stores the network name and any newly typed password for both modes. "
                        + "Auto-connect registers a Wi-Fi suggestion with the saved values; "
                        + "local-only mode uses them each time you approve a reconnect.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String typed = passField.getText().toString();
                // The field starts empty, so an empty field means "keep the
                // stored passphrase"; only a newly typed value replaces it.
                ProfileTable.Outcome saved = Profiles.save(MainActivity.this,
                        ssidField.getText().toString(), null,
                        typed.isEmpty() ? null : typed);
                if (!saved.ok()) {
                    DiagReasons.Failure failure = DiagReasons.profileFailure(saved.error());
                    Diag.event(MainActivity.this, failure.level(), failure.code(),
                            "profile save refused: "
                                    + DiagReasons.profileDetail(saved.error()));
                    toast("Could not save: " + saved.error());
                    return;
                }
                sendServiceAction(MonitorService.ACTION_RELOAD_LOCAL);
                ssidField.setText(saved.profile().ssid());
                passField.setText("");
                passField.setHint(passphraseHint());
                toast("Saved: " + saved.profile().ssid());
                refreshStatus();
            }
        });
        return scrollOf(root);
    }

    private View pageDiagnostics() {
        LinearLayout root = column();
        pageHeader(root, "Diagnostics", "What the keeper did, and what it is missing. "
                + "The password, the pairing code and the control token are never recorded.");

        root.addView(sectionLabel("Snapshot"));
        diagStatus = monospace("");
        root.addView(diagStatus, matchWrap());
        TextView where = text("Also available over adb: content call --uri "
                + "content://io.github.mbanucu.wirelessadbkeeper.control --method diag", 12, MUTED);
        where.setTextIsSelectable(true);
        where.setLineSpacing(dp(2), 1f);
        where.setLayoutParams(spaced(matchWrap(), 6));
        root.addView(where);

        root.addView(sectionLabel("Verbose logging"));
        verboseToggle = new Switch(this);
        verboseToggle.setText("Enable verbose logging");
        verboseToggle.setTextSize(15);
        verboseToggle.setTextColor(TEXT);
        verboseToggle.setChecked(VerboseLog.isEnabled(VerboseLogStore.of(this)));
        verboseListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setVerbose(isChecked);
            }
        };
        verboseToggle.setOnCheckedChangeListener(verboseListener);
        root.addView(verboseToggle, matchWrap());

        verboseNote = text("", 12, BAD);
        verboseNote.setLineSpacing(dp(2), 1f);
        verboseNote.setLayoutParams(spaced(matchWrap(), 6));
        root.addView(verboseNote);

        TextView verboseHelp = text("Off by default. While on, the journal also records "
                + "every pin tick, settings change, network transition and notification "
                + "update, so it is much noisier than usual. It turns itself off after one "
                + "hour or " + VerboseLog.MAX_EVENTS + " events.", 12, MUTED);
        verboseHelp.setLineSpacing(dp(2), 1f);
        verboseHelp.setLayoutParams(spaced(matchWrap(), 6));
        root.addView(verboseHelp);
        updateVerboseUi();

        root.addView(sectionLabel("Journal length"));
        retentionGroup = new RadioGroup(this);
        retentionGroup.setOrientation(RadioGroup.VERTICAL);
        for (DiagRetention.Mode mode : DiagRetention.Mode.values()) {
            RadioButton button = new RadioButton(this);
            button.setText(mode.label());
            button.setTextSize(14);
            button.setTextColor(TEXT);
            button.setTag(mode);
            button.setId(View.generateViewId());
            retentionGroup.addView(button, matchWrap());
        }
        retentionListener = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View checked = group.findViewById(checkedId);
                if (checked != null && checked.getTag() instanceof DiagRetention.Mode) {
                    setRetention((DiagRetention.Mode) checked.getTag());
                }
            }
        };
        retentionGroup.setOnCheckedChangeListener(retentionListener);
        root.addView(retentionGroup, matchWrap());
        TextView retentionHelp = text("The default keeps the newest 200 events, or 32 KiB. "
                + "\"Last day\" keeps events from the last 24 hours, still bounded by 32 KiB so "
                + "verbose logging cannot fill the disk. Off stops every journal write; the "
                + "existing file is left as it is.", 12, MUTED);
        retentionHelp.setLineSpacing(dp(2), 1f);
        retentionHelp.setLayoutParams(spaced(matchWrap(), 6));
        root.addView(retentionHelp);
        updateRetentionUi();

        root.addView(sectionLabel("Recent events"));
        diagJournal = monospace("");
        root.addView(diagJournal, matchWrap());
        TextView keep = text("Newest last. The file lives in device-protected storage, so a "
                + "boot before the first unlock is recorded too.", 12, MUTED);
        keep.setLineSpacing(dp(2), 1f);
        keep.setLayoutParams(spaced(matchWrap(), 6));
        root.addView(keep);

        root.addView(sectionLabel("Previous build"));
        diagPreviousNote = text("", 12, MUTED);
        diagPreviousNote.setLineSpacing(dp(2), 1f);
        diagPreviousNote.setLayoutParams(spaced(matchWrap(), 6));
        diagPreviousNote.setVisibility(View.GONE);
        root.addView(diagPreviousNote);
        diagPrevious = monospace("");
        diagPrevious.setVisibility(View.GONE);
        root.addView(diagPrevious, matchWrap());

        root.addView(sectionLabel("Actions"));
        action(root, "Copy diagnostics",
                "Copies the snapshot and the events as text, ready to paste into a report.",
                true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDiagnostics();
            }
        });
        action(root, "Share diagnostics",
                "Sends the snapshot and the events as one text block, for a GitHub issue.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareDiagnostics();
            }
        });
        action(root, "Refresh",
                "Reads the snapshot and the journal again.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDiag();
                toast("Diagnostics refreshed");
            }
        });
        return scrollOf(root);
    }

    /** A small selectable monospace block, for key=value output. */
    private TextView monospace(String value) {
        TextView view = text(value, 12, TEXT);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setLineSpacing(dp(2), 1f);
        view.setBackground(rounded(CARD_BG, dp(14), 0, 0));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private void refreshDiag() {
        updateVerboseUi();
        updateRetentionUi();
        if (diagStatus == null || diagJournal == null) return;
        try {
            diagStatus.setText(Diag.status(this));
        } catch (Throwable t) {
            diagStatus.setText("snapshot unavailable: " + t);
        }
        try {
            String journal = Diag.journal(this);
            diagJournal.setText(journal.isEmpty()
                    ? "No events yet. The monitor writes here at boot and whenever a state changes."
                    : journal);
        } catch (Throwable t) {
            diagJournal.setText("journal unavailable: " + t);
        }
        try {
            String marker = Diag.previousMarker(this);
            boolean hasPrevious = !marker.isEmpty();
            diagPreviousNote.setText(marker);
            diagPreviousNote.setVisibility(hasPrevious ? View.VISIBLE : View.GONE);
            diagPrevious.setText(hasPrevious ? Diag.previousJournal(this) : "");
            diagPrevious.setVisibility(hasPrevious ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            diagPreviousNote.setText("previous build unavailable: " + t);
            diagPreviousNote.setVisibility(View.VISIBLE);
            diagPrevious.setText("");
            diagPrevious.setVisibility(View.GONE);
        }
    }

    private void copyDiagnostics() {
        String all = Diag.report(this);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("No clipboard service");
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Wireless ADB Keeper diagnostics", all));
            toast("Diagnostics copied");
        } catch (Throwable t) {
            toast("Could not copy: " + t);
        }
    }

    /** One text block, so the paste into a GitHub issue keeps the snapshot and journal together. */
    private void shareDiagnostics() {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Wireless ADB Keeper diagnostics");
            send.putExtra(Intent.EXTRA_TEXT, Diag.report(this));
            startActivity(Intent.createChooser(send, "Share diagnostics"));
        } catch (Throwable t) {
            toast("Could not share: " + t);
        }
    }

    /** Applies a journal-length choice; the next append trims to it. */
    private void setRetention(DiagRetention.Mode mode) {
        DiagRetention.Mode before = DiagRetentionStore.mode(this);
        if (before == mode) return;
        if (mode == DiagRetention.Mode.OFF) {
            // Record the change before off takes effect, then stop all writes.
            Diag.info(this, "diag.retention.off", "journal writing off; "
                    + Diag.count(this) + " events kept on disk");
            DiagRetentionStore.setMode(this, mode);
        } else {
            DiagRetentionStore.setMode(this, mode);
            Diag.info(this, "diag.retention." + mode.value(),
                    "journal length set to " + mode.label());
        }
        toast("Journal length: " + mode.label());
        updateRetentionUi();
        refreshDiag();
    }

    /** Syncs the radio buttons with the stored mode without re-firing the listener. */
    private void updateRetentionUi() {
        if (retentionGroup == null) return;
        DiagRetention.Mode mode = DiagRetentionStore.mode(this);
        retentionGroup.setOnCheckedChangeListener(null);
        for (int i = 0; i < retentionGroup.getChildCount(); i++) {
            View child = retentionGroup.getChildAt(i);
            if (child instanceof RadioButton && mode == child.getTag()) {
                if (retentionGroup.getCheckedRadioButtonId() != child.getId()) {
                    retentionGroup.check(child.getId());
                }
            }
        }
        retentionGroup.setOnCheckedChangeListener(retentionListener);
    }

    /**
     * Applies the toggle: a fresh window when on, closed immediately when off.
     *
     * <p>The on/off events are written here rather than through
     * {@link Diag#verbose}: they are one-per-toggle, not per tick, and the
     * journal should say why the noisy lines that follow are there.
     */
    private void setVerbose(boolean on) {
        VerboseLog.Store store = VerboseLogStore.of(this);
        if (on) {
            VerboseLog.enable(store, System.currentTimeMillis());
            Diag.info(this, "diag.verbose.on", "verbose per-tick logging on for at most"
                    + " one hour or " + VerboseLog.MAX_EVENTS + " events");
            toast("Verbose logging on for one hour");
        } else {
            VerboseLog.disable(store);
            Diag.info(this, "diag.verbose.off", "verbose per-tick logging off");
            toast("Verbose logging off");
        }
        updateVerboseUi();
        refreshDiag();
    }

    /** Syncs the switch and its note with the stored window; never re-fires the toggle. */
    private void updateVerboseUi() {
        if (verboseToggle == null || verboseNote == null) return;
        VerboseLog.Store store = VerboseLogStore.of(this);
        boolean on = VerboseLog.isEnabled(store);
        if (verboseToggle.isChecked() != on) {
            verboseToggle.setOnCheckedChangeListener(null);
            verboseToggle.setChecked(on);
            verboseToggle.setOnCheckedChangeListener(verboseListener);
        }
        verboseNote.setText(on ? "Verbose logging is on ("
                + VerboseLog.status(store, System.currentTimeMillis())
                + "). Every pin tick, settings change, network transition and notification"
                + " update is being recorded. It turns off by itself." : "");
        verboseNote.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private View pageHelp() {
        LinearLayout root = column();
        pageHeader(root, "Help", "One-time setup, background behaviour, and options.");

        // Pairing lives in src-pairing, which only the Gradle build compiles, so
        // the button appears only where it can work.
        if (hasPairing()) {
            root.addView(sectionLabel("App key"));
            action(root, "Pair this phone",
                    "One-time setup: gives the app its own ADB key, trusted by this phone, "
                            + "so pinning the port no longer needs Termux. Opens Developer "
                            + "options' pairing dialog instructions; the 6-digit code goes in "
                            + "there.",
                    true, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                startActivity(new Intent().setClassName(MainActivity.this,
                                        "io.github.mbanucu.wirelessadbkeeper.PairActivity"));
                            } catch (Exception e) {
                                toast("Could not open pairing: " + e);
                            }
                        }
                    });
        }

        root.addView(sectionLabel("Keep-alive"));
        action(root, "Schedule keep-alive (every 15 min)",
                "Registers the periodic JobScheduler fallback that re-applies wireless "
                        + "debugging every 15 minutes. It is registered automatically at boot; "
                        + "this button is for doing it by hand.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scheduleJobs(MainActivity.this);
                toast("Keep-alive scheduled");
                refreshStatus();
            }
        });

        action(root, "Open Developer options",
                "Opens Android's Developer options, where Wireless debugging and its pairing "
                        + "codes live. Nothing is changed by opening it.",
                false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    toast("Could not open settings: " + e);
                }
            }
        });

        root.addView(sectionLabel("How it works"));
        TextView help = text("One-time setup (already done if the status shows permission granted):\n\n"
                + "adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.WRITE_SECURE_SETTINGS\n\n"
                + "How it works:\n"
                + "- A foreground service watches for new Wi-Fi connections and re-enables wireless debugging immediately.\n"
                + "- It also notices if wireless debugging is turned off while Wi-Fi is connected.\n"
                + "- A periodic job and boot receiver re-apply the setting as a fallback.\n"
                + "- Auto-connect mode registers a Wi-Fi suggestion with the saved password (none for an open "
                + "network): approve the system notification once, then Android reconnects silently, even after "
                + "reboots. It is marked metered so mobile data stays the default route.\n"
                + "- Local-only mode instead holds the network as a local link that can never carry internet, but "
                + "Android makes you approve a dialog on every reconnect.\n"
                + "- The fixed port 5555 is kept by Termux's own adb, which is already paired with this "
                + "phone: when the port is closed the app asks Termux to run "
                + "wireless-adb-keeper/pin-adb-port.sh, which sends 'adb tcpip 5555'. An app cannot "
                + "send that command itself. One-time grant: pm grant io.github.mbanucu.wirelessadbkeeper "
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
        if (index == PAGE_DIAG) {
            refreshDiag();
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

    /** Whether a passphrase is stored, without putting it back on screen. */
    private String passphraseHint() {
        String saved = LocalWifi.passphrase(this);
        return saved == null || saved.isEmpty()
                ? "Password" : "Saved - type a new password to replace";
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

    /** Adds a full-width action with its own "i" note underneath. */
    private Button action(LinearLayout root, String label, String help, boolean primary,
                          View.OnClickListener listener) {
        Button b = primary ? primary(label, listener) : button(label, listener);
        root.addView(infoRow(b, help, 8));
        return b;
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

    /** Whether this APK carries the libadb-backed pairing screen. */
    private static boolean hasPairing() {
        try {
            Class.forName("io.github.mbanucu.wirelessadbkeeper.Pairing");
            return true;
        } catch (Throwable e) {
            return false;
        }
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

    private void turnWifiOn() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = WifiRadio.enable(MainActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result == null) {
                            toast("Turn Wi-Fi on in the panel that opens");
                            try {
                                startActivity(WifiRadio.panel());
                            } catch (Exception e) {
                                toast("Could not open Wi-Fi settings: " + e);
                            }
                            return;
                        }
                        toast(result);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                refreshStatus();
                            }
                        }, 2500);
                    }
                });
            }
        }).start();
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

        boolean permission = Ensurer.hasPermission(this);
        boolean wifi = Ensurer.isWifiConnected(this);
        boolean wireless = Ensurer.isWirelessDebuggingOn(this);
        boolean running = MonitorService.running;
        boolean autoMode = LocalWifi.isAutoMode(this);
        boolean job = isJobScheduled();
        String location = LocalWifi.supported()
                ? (LocalWifi.hasLocationPermission(this) ? "granted" : "not granted") : null;
        String port = PinnedPort.state();
        String pinResult = PinnedPort.result();
        String wifiResult = WifiRadio.result();
        String link = running ? LocalWifi.state : "off";
        String detail = running ? LocalWifi.detail : null;

        // Rebuild only on a real change, so an expanded note is not thrown away
        // and the two-second tick does not churn views.
        StringBuilder key = new StringBuilder()
                .append(permission).append('|').append(location).append('|').append(wifi)
                .append('|').append(wireless).append('|').append(running).append('|')
                .append(port).append('|').append(pinResult).append('|')
                .append(wifiResult).append('|').append(autoMode).append('|').append(link)
                .append('|').append(detail).append('|').append(job);
        if (key.toString().equals(lastStatusKey)) return;
        lastStatusKey = key.toString();

        statusBox.removeAllViews();
        addRow("Permission", permission ? "granted" : "not granted", permission ? OK : BAD);
        if (location != null) {
            addRow("Location", location, "granted".equals(location) ? OK : BAD);
        }
        addRow("Wi-Fi", wifi ? "connected" : "not connected", wifi ? OK : null);
        addRow("Wireless", wireless ? "on" : "off", wireless ? OK : BAD);
        addRow("Service", running ? "running" : "stopped", running ? OK : BAD);
        addRow("Pinned port", port);
        addRow("Pin result", pinResult, resultColor(pinResult));
        if (!"none".equals(wifiResult)) {
            addRow("Wi-Fi script", wifiResult, resultColor(wifiResult));
        }
        addRow("Mode", autoMode ? "auto-connect" : "local-only");
        if (!autoMode) {
            addRow("Local link", link);
        }
        if (detail != null && !detail.isEmpty()) {
            addRow("Last result", detail);
        }
        addRow("Job", job ? "scheduled" : "not scheduled");

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

    /** Green for a script that said it worked, red for a script error. */
    private Integer resultColor(String value) {
        if (value == null || "none".equals(value)) return null;
        if (value.startsWith("error=") || value.startsWith("no RESULT")) return BAD;
        if (value.contains("(exit 0)")) return OK;
        return null;
    }

    // A label column of fixed width keeps every value lined up without monospace.
    private void addRow(String label, String value, Integer color) {
        if (statusBox == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView name = text(label, 13, MUTED);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(name);

        TextView val = text(value, 14, color == null ? TEXT : color);
        val.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val);

        statusBox.addView(infoRow(row, statusHint(label), 0), matchWrap());
    }

    /**
     * Wraps one row of content with a circled "i" that expands a plain-language
     * note underneath. Expanding in place keeps the page still, and every note
     * stays readable instead of sitting behind a dialog.
     */
    private View infoRow(View content, String hint, int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(content, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean wasOpen = openHints.contains(hint);
        final TextView detail = text(hint, 12, MUTED);
        detail.setLineSpacing(dp(2), 1f);
        detail.setPadding(0, dp(6), dp(6), dp(2));
        detail.setVisibility(wasOpen ? View.VISIBLE : View.GONE);

        final TextView icon = text("i", 14, wasOpen ? ACCENT : MUTED);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setContentDescription("Explain this");
        icon.setBackground(rounded(0x00000000, dp(18), dp(1),
                wasOpen ? ACCENT : 0xFFC3C8D2));
        icon.setOnClickListener(new View.OnClickListener() {
            private boolean open = wasOpen;

            @Override
            public void onClick(View v) {
                open = !open;
                if (open) {
                    openHints.add(hint);
                } else {
                    openHints.remove(hint);
                }
                detail.setVisibility(open ? View.VISIBLE : View.GONE);
                icon.setTextColor(open ? ACCENT : MUTED);
                ((GradientDrawable) icon.getBackground()).setStroke(
                        dp(1), open ? ACCENT : 0xFFC3C8D2);
            }
        });
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconLp.leftMargin = dp(8);
        row.addView(icon, iconLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(spaced(matchWrap(), topDp));
        box.addView(row, matchWrap());
        box.addView(detail, matchWrap());
        return box;
    }

    private String statusHint(String label) {
        if ("Permission".equals(label)) {
            return "WRITE_SECURE_SETTINGS, granted once over adb with pm grant. Without it the "
                    + "app cannot change wireless debugging at all, so every other row reads off.";
        }
        if ("Location".equals(label)) {
            return "Android 10+ requires Location before an app may read or change Wi-Fi "
                    + "networks. It is used for Wi-Fi only, never to track where you are.";
        }
        if ("Wi-Fi".equals(label)) {
            return "Whether the phone is on a Wi-Fi network right now. The keeper only "
                    + "re-enables wireless debugging while this is connected.";
        }
        if ("Wireless".equals(label)) {
            return "Android's Wireless debugging switch in Developer options. This is the "
                    + "setting the app exists to keep on.";
        }
        if ("Service".equals(label)) {
            return "The foreground service that watches Wi-Fi and re-applies wireless "
                    + "debugging the moment it is needed. Stopping it leaves only the job.";
        }
        if ("Pinned port".equals(label)) {
            return "Whether adbd answers on 127.0.0.1:5555, the fixed port a laptop connects "
                    + "to. It is kept open by Termux's adb, not by this app.";
        }
        if ("Pin result".equals(label)) {
            return "The last line Termux's pin-adb-port.sh printed, plus its exit code and "
                    + "the first stderr line. Termux 0.109+ sends this back through "
                    + "RUN_COMMAND_PENDING_INTENT; older builds show 'none' and only the port "
                    + "probe is available, which is journalled as pin.fallback.";
        }
        if ("Wi-Fi script".equals(label)) {
            return "The last RESULT line from wifi-on.sh, which the Turn Wi-Fi on button runs "
                    + "through Termux. It reports whether svc wifi enable left Wi-Fi on.";
        }
        if ("Mode".equals(label)) {
            return "How the phone rejoins the network. auto-connect registers a Wi-Fi "
                    + "suggestion Android applies silently; local-only holds a local link and "
                    + "needs a tap each time. Both use the saved password, or none for an open "
                    + "network.";
        }
        if ("Local link".equals(label)) {
            return "The state of the last local-only connection request.";
        }
        if ("Last result".equals(label)) {
            return "The most recent thing the app did, or was told by Android, during a "
                    + "connection attempt.";
        }
        if ("Job".equals(label)) {
            return "A 15-minute JobScheduler task that re-applies wireless debugging as a "
                    + "fallback, including after a reboot.";
        }
        return "No description for this row yet.";
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

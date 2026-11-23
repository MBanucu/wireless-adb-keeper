package io.github.mbanucu.wirelessadbkeeper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The one-time pairing screen: type the code Android is showing and the app
 * becomes its own trusted ADB client.
 *
 * This screen only exists in the Gradle build, because pairing needs libadb.
 * The Termux-backed `build.sh` APK does not declare this activity at all; see
 * the manifest staging in build.gradle.kts.
 */
public class PairActivity extends Activity {
    private static final int ACCENT = 0xFF3B5BDB;
    private static final int TEXT = 0xFF111827;
    private static final int MUTED = 0xFF6B7280;
    private static final int OK = 0xFF15803D;
    private static final int BAD = 0xFFB91C1C;

    private EditText codeField;
    private TextView resultView;
    private Button pairButton;
    private Button verifyButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));

        TextView overline = new TextView(this);
        overline.setText("WIRELESS ADB KEEPER");
        overline.setTextSize(10);
        overline.setTextColor(MUTED);
        overline.setTypeface(Typeface.DEFAULT_BOLD);
        overline.setLetterSpacing(0.12f);
        root.addView(overline);

        TextView title = new TextView(this);
        title.setText("Pair this phone");
        title.setTextSize(24);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(2), 0, dp(4));
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Do this once. In Developer options open \"Wireless debugging\" and tap "
                + "\"Pair device with pairing code\" - leave that dialog up, then type its "
                + "6-digit code below. The app gets its own ADB key trusted by this phone, "
                + "so it no longer needs Termux to pin the port.");
        intro.setTextSize(14);
        intro.setTextColor(MUTED);
        intro.setLineSpacing(dp(2), 1f);
        root.addView(intro);

        codeField = new EditText(this);
        codeField.setHint("6-digit pairing code");
        codeField.setTextSize(18);
        codeField.setSingleLine(true);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setLetterSpacing(0.2f);
        codeField.setPadding(0, dp(12), 0, dp(12));
        root.addView(codeField, matchWrap(dp(18)));

        pairButton = button("Pair", ACCENT, Color.WHITE);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("Pairing...", new Task() {
                    @Override
                    public String run() {
                        return Pairing.pair(PairActivity.this, codeField.getText().toString());
                    }
                });
            }
        });
        root.addView(pairButton, matchWrap(dp(10)));

        verifyButton = button("Test connection", 0xFFE5E7EB, TEXT);
        verifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("Trying the trusted connection...", new Task() {
                    @Override
                    public String run() {
                        return Pairing.verify(PairActivity.this);
                    }
                });
            }
        });
        root.addView(verifyButton, matchWrap(dp(8)));

        Button settings = button("Open Developer options", 0xFFE5E7EB, TEXT);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    show("Could not open settings: " + e, BAD);
                }
            }
        });
        root.addView(settings, matchWrap(dp(8)));

        resultView = new TextView(this);
        resultView.setTextSize(13);
        resultView.setTextColor(MUTED);
        resultView.setLineSpacing(dp(2), 1f);
        resultView.setTextIsSelectable(true);
        resultView.setPadding(0, dp(18), 0, 0);
        root.addView(resultView, matchWrap(0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(foreground);
        b.setMinHeight(dp(46));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private interface Task {
        String run();
    }

    /** One call at a time: both pairing and verifying hold the network open. */
    private void run(final String busy, final Task task) {
        pairButton.setEnabled(false);
        verifyButton.setEnabled(false);
        show(busy, MUTED);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = task.run();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean good = result.startsWith("Paired") || result.startsWith("Connected");
                        show(result, good ? OK : BAD);
                        pairButton.setEnabled(true);
                        verifyButton.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void show(String text, int color) {
        resultView.setTextColor(color);
        resultView.setText(text);
    }
}

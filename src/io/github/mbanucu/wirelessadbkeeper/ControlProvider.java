package io.github.mbanucu.wirelessadbkeeper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.util.Map;

/**
 * Runs the keeper's actions from adb instead of a finger.
 *
 *   adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
 *       --method status
 *   adb shell content call --uri ... --method enable
 *
 * A content provider rather than a broadcast receiver because these commands
 * have to work when the app was force-stopped or only just installed: the
 * platform drops broadcasts to an app in that state, but a provider is started
 * on demand. It also keeps the caller's uid visible, so the guard below is a
 * real check and not a guess.
 *
 * The reply bundle carries "code" (0 ok, 4xx/5xx error) and "data": one
 * key=value line per field for `status`, one line for everything else.
 */
public class ControlProvider extends ContentProvider {
    private static final String TAG = Ensurer.TAG;
    public static final String AUTHORITY = "io.github.mbanucu.wirelessadbkeeper.control";

    private static final int UID_ROOT = 0;
    private static final int UID_SYSTEM = 1000;
    private static final int UID_SHELL = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int sender = Binder.getCallingUid();
        if (sender != UID_ROOT && sender != UID_SYSTEM && sender != UID_SHELL
                && sender != Process.myUid()) {
            Log.w(TAG, "control: refused uid " + sender + " for " + method);
            return reply(403, "error=refused: only adb shell, root or system may control the app");
        }
        if (method == null) {
            return reply(400, "error=missing method; use status, enable, wifi_on, ...");
        }

        Bundle in = extras == null ? Bundle.EMPTY : extras;
        String mode = in.getString("mode");
        // The positional value is the SSID for save_wifi and the mode for
        // set_mode; the password only travels as a named extra.
        String ssid = in.getString("ssid", "save_wifi".equals(method) ? arg : null);
        String passphrase = in.getString("passphrase");

        int code;
        String data;
        try {
            data = execute(getContext(), method, mode, ssid, passphrase);
            code = data.startsWith("error=") ? 400 : 0;
        } catch (Throwable t) {
            Log.e(TAG, "control failed", t);
            code = 500;
            data = "error=" + t;
        }
        Log.i(TAG, "control " + method + ": " + oneLine(data));
        return reply(code, data);
    }

    private static String execute(android.content.Context c, String method, String mode,
                                  String ssid, String passphrase) {
        if ("status".equals(method)) {
            return format(Keeper.status(c));
        }
        if ("enable".equals(method)) {
            return "result=" + Keeper.enable(c);
        }
        if ("wifi_on".equals(method)) {
            return Keeper.wifiOn(c);
        }
        if ("service_start".equals(method)) {
            return Keeper.service(c, true);
        }
        if ("service_stop".equals(method)) {
            return Keeper.service(c, false);
        }
        if ("connect".equals(method)) {
            return Keeper.connectNow(c);
        }
        if ("set_mode".equals(method)) {
            return Keeper.setMode(c, mode);
        }
        if ("save_wifi".equals(method)) {
            return Keeper.saveNetwork(c, ssid, passphrase);
        }
        if ("pin_port".equals(method)) {
            return Keeper.pinPort(c);
        }
        if ("schedule".equals(method)) {
            return Keeper.schedule(c);
        }
        return "error=unknown method " + method;
    }

    private static String format(Map<String, String> status) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : status.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(e.getKey()).append('=').append(oneLine(e.getValue()));
        }
        return out.toString();
    }

    /** Keeps one field on one line so the reply stays parseable. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static Bundle reply(int code, String data) {
        Bundle out = new Bundle();
        out.putInt("code", code);
        out.putString("data", data);
        return out;
    }

    // Nothing is stored here; these exist because a provider must implement them.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

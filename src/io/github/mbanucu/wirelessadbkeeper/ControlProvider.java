package io.github.mbanucu.wirelessadbkeeper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
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
 * The provider is the path that works when the app was force-stopped or only
 * just installed: the platform drops broadcasts to an app in that state, but a
 * provider is started on demand. It also keeps the caller's uid visible, so the
 * guard below is a real check and not a guess. {@link ControlReceiver} is the
 * app-reachable twin for the case the `content` command cannot serve.
 *
 * The reply bundle carries "code" (0 ok, 4xx/5xx error) and "data": one
 * key=value line per field for `status` and `pubkey`, one line for everything
 * else.
 *
 * <p>The caller gate is uid based (shell/root/system/self) with a control token
 * as the alternative for an ordinary app. {@code --method token} mints that
 * token and is shell-only. {@link ControlReceiver} runs the same dispatch over
 * an ordered broadcast, because the `content` command cannot be used from an
 * app uid.
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
        Result r = handle(getContext(), Binder.getCallingUid(), method, arg, extras);
        return reply(r.code, r.data);
    }

    /**
     * The gate and dispatch shared with {@link ControlReceiver}.
     *
     * <p>The uid check stays first: shell, root, system and the app itself do
     * not need a token, so every command that worked before still works. An
     * ordinary app may present the token instead. A missing token and a wrong
     * token take the same path and produce the same 403; only the shell-only
     * "token" method ever mints one.
     */
    static Result handle(Context c, int sender, String method, String arg, Bundle extras) {
        Bundle in = extras == null ? Bundle.EMPTY : extras;
        String token = in.getString("token");
        boolean privileged = sender == UID_ROOT || sender == UID_SYSTEM
                || sender == UID_SHELL || sender == Process.myUid();
        boolean tokenOk = !privileged && ControlToken.accept(c, token);
        if (!privileged && !tokenOk) {
            Log.w(TAG, "control: refused " + caller(sender)
                    + " token=" + ControlToken.prefix(token) + " for " + method);
            return new Result(403,
                    "error=refused: only adb shell, root or system may control the app");
        }
        if (method == null) {
            return new Result(400, "error=missing method; use status, enable, wifi_on, pubkey, ...");
        }
        if (!privileged && ("token".equals(method) || "pubkey".equals(method))) {
            // Both mint or reveal identity material. A token holder is trusted
            // to control the keeper, not to bootstrap another copy of itself.
            Log.w(TAG, "control: refused " + caller(sender)
                    + " token=" + ControlToken.prefix(token)
                    + " for shell-only " + method);
            return new Result(403, "error=refused: " + method
                    + " may only be requested from adb shell, root or system");
        }
        Log.i(TAG, "control: accepted " + caller(sender)
                + " token=" + ControlToken.prefix(token)
                + " via " + (privileged ? "uid" : "token") + " for " + method);

        // The positional value is the SSID for save_wifi and the mode for
        // set_mode; the password only travels as a named extra. Reading the mode
        // only from the extras made `keeper.sh mode local` pass null, which
        // setMode treats as "toggle" — so asking for the mode it was already in
        // switched it to the other one.
        String mode = in.getString("mode", "set_mode".equals(method) ? arg : null);
        String ssid = in.getString("ssid", "save_wifi".equals(method) ? arg : null);
        String passphrase = in.getString("passphrase");

        try {
            String data = "token".equals(method) ? tokenReply(c)
                    : execute(c, method, mode, ssid, passphrase);
            int code = data.startsWith("error=") ? 400 : 0;
            if ("token".equals(method)) {
                // The one reply that is the secret. Keep it out of logcat even
                // though the accept/refuse lines above are useful.
                Log.i(TAG, "control token: "
                        + (code == 0 ? "minted (not logged)" : oneLine(data)));
            } else {
                Log.i(TAG, "control " + method + ": " + oneLine(data));
            }
            return new Result(code, data);
        } catch (Throwable t) {
            Log.e(TAG, "control failed", t);
            return new Result(500, "error=" + t);
        }
    }

    /** The provider has a uid; the broadcast path passes -1 and uses the token. */
    private static String caller(int sender) {
        return sender < 0 ? "broadcast sender" : "uid " + sender;
    }

    /** The shell-only reply: the token plus where it lives, never logged. */
    private static String tokenReply(Context c) {
        try {
            return "token=" + ControlToken.issue(c)
                    + "\npath=" + ControlToken.file(c);
        } catch (Exception e) {
            return "error=" + e.getMessage();
        }
    }

    private static String execute(Context c, String method, String mode,
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
        if ("pubkey".equals(method)) {
            return Keeper.pubkey(c);
        }
        if ("diag".equals(method)) {
            return Diag.report(c);
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

    /** The provider wraps this in a Bundle; the broadcast returns it directly. */
    static final class Result {
        final int code;
        final String data;

        Result(int code, String data) {
            this.code = code;
            this.data = data;
        }
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

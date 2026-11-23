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
 * key=value line per field for `status`, `profiles` and `pubkey`, one line for
 * everything else.
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
                    + " token=" + presented(token) + " for " + method);
            return new Result(403, "error=refused: control needs adb shell, root,"
                    + " system or a valid control token");
        }
        if (method == null) {
            return new Result(400, "error=missing method; use status, enable, wifi_on, pubkey, ...");
        }
        if (!privileged && ("token".equals(method) || "pubkey".equals(method))) {
            // Both mint or reveal identity material. A token holder is trusted
            // to control the keeper, not to bootstrap another copy of itself.
            Log.w(TAG, "control: refused " + caller(sender)
                    + " token=" + presented(token)
                    + " for shell-only " + method);
            return new Result(403, "error=refused: " + method
                    + " may only be requested from adb shell, root or system");
        }
        Log.i(TAG, "control: accepted " + caller(sender)
                + " token=" + presented(token)
                + " via " + (privileged ? "uid" : "token") + " for " + method);

        // The positional value is the SSID for save_wifi and the mode for
        // set_mode; the password only travels as a named extra. ControlArgs is
        // where the fallback lives, so `keeper.sh mode local` cannot silently
        // become a toggle again.
        ControlArgs args = ControlArgs.of(method, arg, name -> in.getString(name));

        try {
            String data = args.method == ControlArgs.Method.TOKEN ? tokenReply(c)
                    : execute(c, args);
            int code = data.startsWith("error=") ? 400 : 0;
            if (args.method == ControlArgs.Method.TOKEN) {
                // The one reply that is the secret. Keep it out of logcat even
                // though the accept/refuse lines above are useful.
                Log.i(TAG, "control token: "
                        + (code == 0 ? "minted (not logged)" : ControlText.oneLine(data)));
            } else {
                Log.i(TAG, "control " + method + ": " + ControlText.oneLine(data));
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

    /**
     * Whether a token was presented, for the log. Not even a prefix: a valid
     * token's characters are the one thing that must not reach logcat.
     */
    private static String presented(String token) {
        return token == null || token.isEmpty() ? "none" : "present";
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

    private static String execute(Context c, ControlArgs args) {
        switch (args.method) {
            case STATUS:
                return ControlText.lines(Keeper.status(c));
            case ENABLE:
                return "result=" + Keeper.enable(c);
            case WIFI_ON:
                return Keeper.wifiOn(c);
            case SERVICE_START:
                return Keeper.service(c, true);
            case SERVICE_STOP:
                return Keeper.service(c, false);
            case CONNECT:
                return Keeper.connectNow(c);
            case SET_MODE:
                return Keeper.setMode(c, args.mode);
            case SAVE_WIFI:
                return args.id == null || args.id.isEmpty()
                        ? Keeper.saveNetwork(c, args.ssid, args.passphrase)
                        : Keeper.saveProfile(c, args.id, args.ssid, args.passphrase);
            case PROFILES:
                return Keeper.profiles(c);
            case REMOVE_PROFILE:
                return Keeper.removeProfile(c, args.id);
            case SET_PRIORITY:
                return Keeper.setPriority(c, args.id, args.priority);
            case PIN_PORT:
                return Keeper.pinPort(c);
            case PUBKEY:
                return Keeper.pubkey(c);
            case DIAG:
                return Diag.report(c);
            case SCHEDULE:
                return Keeper.schedule(c);
            default:
                return "error=unknown method " + args.name;
        }
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

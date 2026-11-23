package io.github.mbanucu.wirelessadbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The control surface an ordinary app uid can actually reach.
 *
 * <p>`content call` looks like the obvious transport, but the `content` binary
 * asks for android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY, which is a
 * signature permission held only by shell and root. Called from Termux the
 * command fails inside getContentProviderExternal(), before
 * {@link ControlProvider} is entered, so a token cannot help that path.
 *
 * <p>An explicit ordered broadcast is allowed from an app. This receiver runs
 * the same dispatch as the provider and returns the reply as the broadcast
 * result for `am broadcast` to print. Exporting it does not widen access:
 * without the token, or with a wrong one, an ordinary app is refused.
 *
 * <p>The token is the whole gate here. BroadcastReceiver does not expose a
 * trustworthy sender uid on every supported release (it is not a Binder call
 * into this process), so this path does not pretend to have one. Shell and root
 * keep their uid gate through {@link ControlProvider}.
 *
 * <p>A force-stopped app receives no broadcasts; the provider called by
 * `adb shell content call` still starts it. That is why `keeper.sh` remains the
 * recovery path and this receiver is the no-adb path.
 */
public class ControlReceiver extends BroadcastReceiver {
    public static final String ACTION = "io.github.mbanucu.wirelessadbkeeper.CONTROL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        Bundle extras = intent.getExtras();
        String method = extras == null ? null : extras.getString("method");
        String arg = extras == null ? null : extras.getString("arg");
        // -1 makes the uid gate a no-op here; the token is the gate. The app
        // logs "broadcast sender" rather than inventing a uid it cannot trust.
        ControlProvider.Result r =
                ControlProvider.handle(context, -1, method, arg, extras);
        // The wrapper sends an ordered broadcast; a plain one has no reply
        // channel, so do not trip the platform's "result during non-ordered
        // broadcast" warning just to throw the answer away.
        if (isOrderedBroadcast()) {
            setResultCode(r.code);
            setResultData(r.data);
        }
    }
}

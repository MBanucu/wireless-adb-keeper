package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The journal-length setting in device-protected storage.
 *
 * <p>Like the verbose toggle it has to be readable at
 * {@code LOCKED_BOOT_COMPLETED}, before the first unlock, so the boot events
 * follow the same rule as every later tick. Device-protected storage also
 * survives a reboot, and the choice is read on each append rather than cached so
 * it reaches a monitor that is already running.
 */
final class DiagRetentionStore {
    static final String PREFS = "diag_retention";
    static final String KEY_MODE = "mode";

    private DiagRetentionStore() {
    }

    static DiagRetention.Mode mode(Context c) {
        if (c == null) return DiagRetention.Mode.DEFAULT;
        SharedPreferences prefs = c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return DiagRetention.Mode.fromValue(prefs.getString(KEY_MODE,
                DiagRetention.Mode.DEFAULT.value()));
    }

    static void setMode(Context c, DiagRetention.Mode mode) {
        if (c == null || mode == null) return;
        c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.value())
                .apply();
    }
}

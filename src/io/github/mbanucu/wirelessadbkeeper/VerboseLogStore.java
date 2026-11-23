package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The verbose toggle in device-protected storage.
 *
 * <p>It has to be readable at {@code LOCKED_BOOT_COMPLETED}, before the first
 * unlock, so the monitor's first ticks already know whether to be noisy. The
 * credential-protected prefs (the Wi-Fi settings) are not readable then, which
 * is why this is not part of {@link LocalWifi#PREFS}. Device-protected storage
 * also survives a reboot, so a toggle left on expires on its own rather than
 * silently staying on.
 */
final class VerboseLogStore implements VerboseLog.Store {
    static final String PREFS = "diag_verbose";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_STARTED_AT = "started_at";
    static final String KEY_EVENTS = "events";

    private final SharedPreferences prefs;

    private VerboseLogStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** The app's store, or null when there is no context to read it from. */
    static VerboseLog.Store of(Context c) {
        if (c == null) return null;
        SharedPreferences prefs = c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new VerboseLogStore(prefs);
    }

    @Override
    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    @Override
    public long startedAtMs() {
        return prefs.getLong(KEY_STARTED_AT, 0L);
    }

    @Override
    public int count() {
        return prefs.getInt(KEY_EVENTS, 0);
    }

    @Override
    public void save(boolean enabled, long startedAtMs, int count) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putLong(KEY_STARTED_AT, startedAtMs)
                .putInt(KEY_EVENTS, count)
                .apply();
    }
}

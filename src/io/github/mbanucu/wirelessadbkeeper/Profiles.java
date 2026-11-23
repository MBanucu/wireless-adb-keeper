package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Android side of the profile store: prefs and {@link PassphraseStore}.
 *
 * <p>{@link ProfileTable} owns every decision; this class only adapts the two
 * stores and keeps the calls under one lock so a UI save and a service read
 * cannot interleave a read-modify-write.
 */
public final class Profiles {

    public static final int CAPACITY = ProfileTable.CAPACITY;

    private Profiles() {
    }

    public static synchronized List<Profile> list(Context c) {
        return table(c).list();
    }

    /** The first profile, which the single-network API reads and applies. */
    public static synchronized Profile active(Context c) {
        return table(c).active();
    }

    public static synchronized Profile bySsid(Context c, String ssid) {
        return table(c).bySsid(ssid);
    }

    /** Saves by SSID: an existing network is updated, never duplicated. */
    public static synchronized ProfileTable.Outcome save(Context c, String ssid,
                                                         String security, String secret) {
        return table(c).save(ssid, security, secret);
    }

    public static synchronized ProfileTable.Outcome add(Context c, String ssid,
                                                        String security, String secret) {
        return table(c).add(ssid, security, secret);
    }

    /** Edits one profile; the id survives a rename. */
    public static synchronized ProfileTable.Outcome update(Context c, String id, String ssid,
                                                           String security, String secret) {
        return table(c).update(id, ssid, security, secret);
    }

    public static synchronized ProfileTable.Outcome remove(Context c, String id) {
        return table(c).remove(id);
    }

    /** Moves a profile to a 1-based position; the order is the try order. */
    public static synchronized ProfileTable.Outcome move(Context c, String id, int priority) {
        return table(c).move(id, priority);
    }

    /** The active SSID, or "" before any network is saved. */
    public static synchronized String ssid(Context c) {
        Profile profile = table(c).active();
        return profile == null ? "" : profile.ssid();
    }

    /** The active passphrase; null means the Keystore cannot decrypt it. */
    static synchronized String secret(Context c) {
        Profile profile = table(c).active();
        return profile == null ? "" : secret(c, profile.id());
    }

    /** A profile's passphrase; null means the Keystore cannot decrypt it. */
    static synchronized String secret(Context c, String id) {
        String value = PassphraseStore.getSecret(c, ProfileTable.secretSlot(id));
        return value == null ? null : value;
    }

    static synchronized boolean setSecret(Context c, String id, String value) {
        return PassphraseStore.putSecret(c, ProfileTable.secretSlot(id), value);
    }

    public static synchronized boolean hasSecret(Context c, String id) {
        String value = secret(c, id);
        return value != null && !value.isEmpty();
    }

    private static ProfileTable table(final Context c) {
        final SharedPreferences prefs = c.getSharedPreferences(
                LocalWifi.PREFS, Context.MODE_PRIVATE);
        ProfileTable.Store store = new ProfileTable.Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void write(Map<String, String> puts, Set<String> removes) {
                SharedPreferences.Editor edit = prefs.edit();
                for (Map.Entry<String, String> entry : puts.entrySet()) {
                    edit.putString(entry.getKey(), entry.getValue());
                }
                for (String key : removes) {
                    edit.remove(key);
                }
                edit.apply();
            }
        };
        ProfileTable.Secrets secrets = new ProfileTable.Secrets() {
            @Override
            public boolean put(String slot, String value) {
                return PassphraseStore.putSecret(c, slot, value);
            }

            @Override
            public void clear(String slot) {
                PassphraseStore.clearSecret(c, slot);
            }

            @Override
            public boolean adoptLegacy(String slot) {
                return PassphraseStore.adoptLegacy(c, slot);
            }
        };
        return new ProfileTable(store, secrets);
    }
}

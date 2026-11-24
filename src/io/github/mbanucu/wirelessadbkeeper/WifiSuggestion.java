package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Silent auto-connect via WifiNetworkSuggestion, one suggestion per profile.
 *
 * Unlike WifiNetworkSpecifier, a suggestion survives reboots and reconnects on
 * its own without a dialog. Android asks for approval exactly once (via a
 * notification); after that the platform owns the connection.
 *
 * The network is marked metered, which keeps it below unmetered networks in the
 * default-route ranking, and an access point without internet access never
 * validates, so mobile data normally stays the default route.
 *
 * <p>The decisions (which profiles to register, which record to rewrite) live
 * in {@link SuggestionRegistry}; this class is the Android adapter: the scan,
 * the suggestion builder and the manager calls.
 */
public final class WifiSuggestion {

    public static final int STATUS_SUCCESS = SuggestionRegistry.STATUS_SUCCESS;

    private static final String PREFS = "wifi_suggestion";
    /** Pre-upgrade builds wrote ssid\0pass, and later ssid\0security\0pass, here. */
    private static final String KEY_LEGACY_APPLIED = "applied";
    /** Hash-only key from the interim build; it cannot rebuild a suggestion. */
    private static final String KEY_HASH_APPLIED = "applied_sha256";
    /** Encrypted in PassphraseStore, never in this prefs file. */
    private static final String SLOT_LEGACY = ProfileTable.LEGACY_APPLIED_SLOT;

    private static final String SEC_WPA2 = SuggestionRecord.SEC_WPA2;
    private static final String SEC_WPA3 = SuggestionRecord.SEC_WPA3;
    private static final String SEC_OWE = SuggestionRecord.SEC_OWE;
    private static final String SEC_OPEN = SuggestionRecord.SEC_OPEN;

    private WifiSuggestion() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Registers every profile with a usable credential and reports one status
     * per profile. The detail is `suggestion <id>: <status>` for each, so a
     * skipped profile names itself instead of failing the whole pass.
     */
    public static SuggestionRegistry.Outcome addAll(Context c) {
        // Before the first unlock the Wi-Fi prefs and the Keystore are not
        // readable. Report that state instead of letting every profile look
        // like "no password" (-2): the caller waits and tries again after the
        // user unlocks, when the suggestion can actually be registered.
        if (!LocalWifi.storageUnlocked(c)) {
            journal(c, "info", "wifi.suggest.locked",
                    "credential storage is locked; the suggestion is registered after"
                            + " the first unlock");
            return SuggestionRegistry.Outcome.locked();
        }
        migrateLegacyPrefs(c);
        WifiManager wm = manager(c);
        if (wm == null) {
            Diag.warn(c, "wifi.suggest.no_service", "WifiManager is unavailable");
            return SuggestionRegistry.Outcome.unavailable();
        }
        List<Profile> profiles = Profiles.list(c);
        List<Profile> resolved = new ArrayList<>(profiles.size());
        for (Profile profile : profiles) {
            resolved.add(configuredSecurity(c, profile));
        }
        SuggestionRegistry.Outcome outcome =
                new SuggestionRegistry(secrets(c), new AndroidManager(c, wm)).addAll(resolved);
        journal(c, outcome);
        Log.i(Ensurer.TAG, "wifi suggestions: " + outcome.detail());
        return outcome;
    }

    /**
     * Removes exactly this profile's suggestion and its applied record. True
     * also when there was nothing to remove; false only when the platform call
     * failed and the record was kept for a retry.
     */
    public static boolean removeOne(Context c, String id) {
        migrateLegacyPrefs(c);
        WifiManager wm = manager(c);
        if (wm == null) return true;
        List<Profile> profiles = Profiles.list(c);
        int removed = new SuggestionRegistry(secrets(c), new AndroidManager(c, wm))
                .removeOne(profiles, id);
        if (removed == SuggestionRegistry.STATUS_REQUEST_FAILED) {
            Diag.warn(c, "wifi.suggest.refused", id
                    + ": could not remove the suggestion; its record was kept for a retry");
            return false;
        }
        Log.i(Ensurer.TAG, "wifi suggestion removed for " + id + ": " + removed);
        return true;
    }

    /** Removes every profile's suggestion, for a switch away from auto-connect. */
    public static int removeAll(Context c) {
        migrateLegacyPrefs(c);
        WifiManager wm = manager(c);
        if (wm == null) return 0;
        int removed = new SuggestionRegistry(secrets(c), new AndroidManager(c, wm))
                .removeAll(Profiles.list(c));
        Log.i(Ensurer.TAG, "wifi suggestions removed: " + removed);
        return removed;
    }

    /**
     * This app's registered suggestions, not "the" suggestion.
     *
     * Android 11+ knows the real count; Android 10 can only remove by record,
     * so there the applied records are the count.
     */
    public static int count(Context c) {
        if (!LocalWifi.storageUnlocked(c)) return -1;
        migrateLegacyPrefs(c);
        WifiManager wm = manager(c);
        if (wm == null) return -1;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
                return existing == null ? 0 : existing.size();
            } catch (Exception e) {
                return -1;
            }
        }
        return new SuggestionRegistry(secrets(c), new AndroidManager(c, wm))
                .count(Profiles.list(c));
    }

    /**
     * A profile's own security wins when it was set; `auto` is decided from the
     * scan, exactly as the single-network build did. WPA3-only means SAE without
     * PSK; a transition AP advertises both and stays WPA2 because it also
     * accepts a WPA2 passphrase.
     */
    private static Profile configuredSecurity(Context c, Profile profile) {
        if (profile.security() != null && !profile.security().isEmpty()) return profile;
        String scanned = scannedSecurity(c, profile.ssid());
        return scanned == null
                ? profile
                : new Profile(profile.id(), profile.ssid(), scanned);
    }

    private static SuggestionRegistry.Secrets secrets(final Context c) {
        return new SuggestionRegistry.Secrets() {
            @Override
            public String get(String slot) {
                return PassphraseStore.getSecret(c, slot);
            }

            @Override
            public boolean put(String slot, String value) {
                return PassphraseStore.putSecret(c, slot, value);
            }

            @Override
            public void clear(String slot) {
                PassphraseStore.clearSecret(c, slot);
            }

            @Override
            public boolean locked() {
                return !LocalWifi.storageUnlocked(c);
            }
        };
    }

    /** One status line per profile, plus the reason for anything not active. */
    private static void journal(Context c, SuggestionRegistry.Outcome outcome) {
        for (SuggestionRegistry.Entry entry : outcome.entries()) {
            if (entry.recordLost()) {
                journal(c, "warn", "wifi.suggest.record_undecryptable",
                        entry.id() + " " + entry.ssid()
                                + ": the applied suggestion record cannot be decrypted;"
                                + " save the network again");
            }
            switch (entry.reason()) {
                case NO_PASSWORD:
                    Log.w(Ensurer.TAG, "wifi suggestion: no password stored for "
                            + entry.ssid());
                    journal(c, "warn", "wifi.suggest.no_password", entry.id() + " "
                            + entry.ssid() + ": auto-connect needs the password");
                    break;
                case DECRYPT_FAILED:
                    journal(c, "warn", "wifi.suggest.decrypt_failed", entry.id() + " "
                            + entry.ssid() + ": the stored passphrase cannot be decrypted;"
                            + " save it again");
                    break;
                case ADDED_UNSEALED:
                    Log.w(Ensurer.TAG, "wifi suggestion: could not encrypt applied record");
                    journal(c, "warn", "wifi.suggest.record_unsealed",
                            entry.id() + " " + entry.ssid());
                    break;
                case READDED:
                    Log.w(Ensurer.TAG, "wifi suggestion: re-registering missing suggestion for "
                            + entry.ssid());
                    journal(c, "warn", "wifi.suggest.missing", entry.id() + " " + entry.ssid()
                            + ": the applied record matched but Android no longer held the"
                            + " suggestion; registered it again");
                    break;
                case CURRENT:
                    Log.i(Ensurer.TAG, "wifi suggestion already present for " + entry.ssid());
                    break;
                default:
                    // ADDED, REFUSED, BUILD_FAILED and REQUEST_FAILED are
                    // journalled where the manager has the status and exception.
                    break;
            }
        }
    }

    /**
     * The applied credentials older builds kept in plaintext in the suggestion
     * prefs, first as `ssid \0 pass` and then as `ssid \0 security \0 pass`.
     * The first touch moves whichever shape is found into the encrypted legacy
     * slot and deletes the plaintext copy. The hash-only record from the interim
     * build is deleted too: it cannot rebuild a suggestion.
     */
    private static void migrateLegacyPrefs(Context c) {
        SharedPreferences store = prefs(c);
        String legacy = store.getString(KEY_LEGACY_APPLIED, null);
        if (legacy != null) {
            String stored = PassphraseStore.getSecret(c, SLOT_LEGACY);
            if (stored == null || stored.isEmpty()) {
                String migrated = SuggestionRecord.migrate(legacy);
                if (!migrated.isEmpty()
                        && PassphraseStore.putSecret(c, SLOT_LEGACY, migrated)) {
                    Log.i(Ensurer.TAG, "wifi suggestion: migrated applied record");
                } else {
                    Log.w(Ensurer.TAG, "wifi suggestion: could not migrate applied record");
                }
            }
            // Never leave the plaintext copy behind, even if encryption failed:
            // the Keystore is also what the passphrase needs, so without it the
            // record cannot be rebuilt anyway.
            store.edit().remove(KEY_LEGACY_APPLIED).apply();
        }
        if (store.contains(KEY_HASH_APPLIED)) {
            store.edit().remove(KEY_HASH_APPLIED).apply();
        }
    }

    /** The Android manager: one suggestion at a time, each journalled by id. */
    private static final class AndroidManager implements SuggestionRegistry.Manager {
        private final Context context;
        private final WifiManager wm;

        AndroidManager(Context context, WifiManager wm) {
            this.context = context;
            this.wm = wm;
        }

        @Override
        public int add(String id, SuggestionRecord record) {
            WifiNetworkSuggestion suggestion;
            try {
                suggestion = suggest(record.ssid(), record.security(), record.passphrase());
            } catch (Exception e) {
                Log.e(Ensurer.TAG, "wifi suggestion: build failed", e);
                Diag.error(context, "wifi.suggest.build_failed", id + " " + record.ssid()
                        + " (" + record.security() + "): " + e);
                return SuggestionRegistry.STATUS_BUILD_FAILED;
            }
            try {
                int status = wm.addNetworkSuggestions(Collections.singletonList(suggestion));
                if (status == STATUS_SUCCESS) {
                    Diag.info(context, "wifi.suggest.added", id + " " + record.ssid()
                            + " (" + record.security() + ")");
                } else {
                    Diag.warn(context, "wifi.suggest.refused", id + " " + record.ssid()
                            + " (" + record.security() + "): status " + status);
                }
                return status;
            } catch (Exception e) {
                Log.e(Ensurer.TAG, "wifi suggestion: addNetworkSuggestions failed", e);
                Diag.error(context, "wifi.suggest.refused", id + " " + record.ssid()
                        + ": request failed: " + e);
                return SuggestionRegistry.STATUS_REQUEST_FAILED;
            }
        }

        @Override
        public int remove(String id, SuggestionRecord record) {
            try {
                wm.removeNetworkSuggestions(Collections.singletonList(
                        suggest(record.ssid(), record.security(), record.passphrase())));
                return 1;
            } catch (Exception e) {
                Log.w(Ensurer.TAG, "wifi suggestion: remove failed for " + id + ": " + e);
                return SuggestionRegistry.STATUS_REQUEST_FAILED;
            }
        }

        @Override
        public int count() {
            if (Build.VERSION.SDK_INT < 30) return -1;
            try {
                List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
                return existing == null ? 0 : existing.size();
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public java.util.Set<String> heldSsids() {
            if (Build.VERSION.SDK_INT < 30) return null;
            try {
                List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
                if (existing == null) return java.util.Collections.emptySet();
                java.util.Set<String> ssids = new java.util.LinkedHashSet<>();
                for (WifiNetworkSuggestion suggestion : existing) {
                    if (suggestion == null) continue;
                    String ssid = suggestion.getSsid();
                    if (ssid != null) ssids.add(ssid);
                }
                return ssids;
            } catch (Exception e) {
                // Unknown is not the same as empty: an unreadable list must not
                // make every profile look missing and be re-registered.
                return null;
            }
        }
    }

    private static WifiNetworkSuggestion suggest(String ssid, String security, String pass) {
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsMetered(true);
        if (SEC_OPEN.equals(security)) {
            // No passphrase: an open AP has nothing to authenticate with.
        } else if (SEC_OWE.equals(security)) {
            // OWE is encrypted but unauthenticated; it still takes no passphrase.
            builder.setIsEnhancedOpen(true);
        } else if (SEC_WPA3.equals(security)) {
            builder.setWpa3Passphrase(pass);
        } else {
            builder.setWpa2Passphrase(pass);
        }
        return builder.build();
    }

    /**
     * What the scan says about this AP, or null when it cannot be read (no
     * Location grant, Wi-Fi off, or the AP is not in the cached results).
     * WPA3-only means SAE without PSK; a transition AP advertises both and is
     * treated as WPA2 on purpose, because it also accepts a WPA2 passphrase.
     * OWE (enhanced open) is not plain open: it is encrypted but needs no key.
     */
    // The permission is checked inline, in the same method as the call, because
    // that is the shape lint can verify; the try below covers a vendor build
    // that refuses the query even with the grant.
    @SuppressWarnings("deprecation")
    private static String scannedSecurity(Context c, String ssid) {
        if (ssid == null || ssid.isEmpty()
                || c.checkSelfPermission(LocalWifi.PERM_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        WifiManager wm = manager(c);
        if (wm == null) return null;
        List<ScanResult> results;
        try {
            results = wm.getScanResults();
        } catch (Exception e) {
            return null;
        }
        if (results == null) return null;
        for (ScanResult r : results) {
            if (r == null || r.SSID == null || !r.SSID.equals(ssid)) continue;
            String caps = r.capabilities == null ? "" : r.capabilities.toUpperCase(Locale.US);
            boolean sae = caps.contains("SAE");
            boolean psk = caps.contains("PSK");
            if (sae && !psk) return SEC_WPA3;
            if (caps.contains("OWE")) return SEC_OWE;
            if (!sae && !psk && !caps.contains("WEP") && !caps.contains("EAP")) {
                return SEC_OPEN;
            }
            return SEC_WPA2;
        }
        return null;
    }

    private static WifiManager manager(Context c) {
        try {
            return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Last reason journalled; add/remove would otherwise repeat it. */
    private static String lastJournal = "";

    private static void journal(Context c, String level, String code, String detail) {
        String key = level + " " + code + " " + detail;
        synchronized (WifiSuggestion.class) {
            if (key.equals(lastJournal)) return;
            lastJournal = key;
        }
        Diag.event(c, level, code, detail);
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Silent auto-connect via WifiNetworkSuggestion.
 *
 * Unlike WifiNetworkSpecifier, a suggestion survives reboots and reconnects on
 * its own without a dialog. Android asks for approval exactly once (via a
 * notification); after that the platform owns the connection.
 *
 * The network is marked metered, which keeps it below unmetered networks in the
 * default-route ranking, and an access point without internet access never
 * validates, so mobile data normally stays the default route.
 */
public final class WifiSuggestion {

    public static final int STATUS_SUCCESS = 0;
    private static final String PREFS = "wifi_suggestion";
    /** Pre-upgrade builds wrote ssid\0pass, and later ssid\0security\0pass, here. */
    private static final String KEY_LEGACY_APPLIED = "applied";
    /** Hash-only key from the interim build; it cannot rebuild a suggestion. */
    private static final String KEY_HASH_APPLIED = "applied_sha256";
    /** Encrypted in PassphraseStore, never in this prefs file. */
    private static final String SLOT_APPLIED = ProfileTable.LEGACY_APPLIED_SLOT;

    private static final String SEC_WPA2 = SuggestionRecord.SEC_WPA2;
    private static final String SEC_WPA3 = SuggestionRecord.SEC_WPA3;
    private static final String SEC_OWE = SuggestionRecord.SEC_OWE;
    private static final String SEC_OPEN = SuggestionRecord.SEC_OPEN;

    private WifiSuggestion() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int add(Context c) {
        String applied = appliedRecord(c);
        WifiManager wm = manager(c);
        if (wm == null) return -1;

        String ssid = LocalWifi.ssid(c);
        String pass = LocalWifi.passphrase(c);
        // A suggestion has to carry the network's security, and auto-connect
        // needs a passphrase for anything that is not open. Only the scan can
        // tell us which, so without one we keep the old WPA2 + password path.
        String scanned = scannedSecurity(c, ssid);
        String security;
        if (SEC_OPEN.equals(scanned)) {
            security = SEC_OPEN;
        } else if (SEC_OWE.equals(scanned)) {
            security = SEC_OWE;
        } else if (pass == null || pass.isEmpty()) {
            Log.w(Ensurer.TAG, "wifi suggestion: no password stored for " + ssid);
            Diag.warn(c, "wifi.suggest.no_password", ssid + ": auto-connect needs the password");
            return -2;
        } else {
            // A WPA3-only AP rejects a WPA2 passphrase; a WPA2/WPA3 transition
            // AP advertises both and also accepts WPA2.
            security = SEC_WPA3.equals(scanned) ? SEC_WPA3 : SEC_WPA2;
        }
        // Open and OWE APs need no secret, even if a stale one is still stored.
        String secret = (SEC_OPEN.equals(security) || SEC_OWE.equals(security)) ? "" : pass;

        // Re-adding an identical suggestion makes Android drop the network for a
        // moment, so only rewrite it when the configuration actually changed.
        String wanted = SuggestionRecord.encode(ssid, security, secret);
        if (wanted.equals(applied) && count(c) > 0) {
            Log.i(Ensurer.TAG, "wifi suggestion already present for " + ssid);
            return STATUS_SUCCESS;
        }

        remove(c);

        WifiNetworkSuggestion suggestion;
        try {
            suggestion = suggest(ssid, security, secret);
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "wifi suggestion: build failed", e);
            Diag.error(c, "wifi.suggest.build_failed", ssid + " (" + security + "): " + e);
            return -3;
        }

        try {
            int status = wm.addNetworkSuggestions(Collections.singletonList(suggestion));
            Log.i(Ensurer.TAG, "wifi suggestion added for " + ssid + " (" + security
                    + "): status " + status);
            if (status == STATUS_SUCCESS) {
                Diag.info(c, "wifi.suggest.added", ssid + " (" + security + ")");
                if (!PassphraseStore.putSecret(c, SLOT_APPLIED, wanted)) {
                    Log.w(Ensurer.TAG, "wifi suggestion: could not encrypt applied record");
                    Diag.warn(c, "wifi.suggest.record_unsealed", ssid);
                }
            } else {
                Diag.warn(c, "wifi.suggest.failed", ssid + " (" + security
                        + "): status " + status);
            }
            return status;
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "wifi suggestion: addNetworkSuggestions failed", e);
            Diag.error(c, "wifi.suggest.failed", ssid + ": " + e);
            return -4;
        }
    }

    public static void remove(Context c) {
        // Read the record before clearing it: on Android 10 this is the only
        // description of the suggestion Android actually holds.
        String applied = appliedRecord(c);
        WifiManager wm = manager(c);
        if (wm == null) return;
        PassphraseStore.clearSecret(c, SLOT_APPLIED);
        try {
            List<WifiNetworkSuggestion> existing = registered(wm, applied);
            if (existing != null && !existing.isEmpty()) {
                wm.removeNetworkSuggestions(existing);
                Log.i(Ensurer.TAG, "wifi suggestions removed: " + existing.size());
            }
        } catch (Exception e) {
            Log.w(Ensurer.TAG, "wifi suggestion: remove failed: " + e);
        }
    }

    public static int count(Context c) {
        String applied = appliedRecord(c);
        WifiManager wm = manager(c);
        if (wm == null) return -1;
        try {
            List<WifiNetworkSuggestion> existing = registered(wm, applied);
            return existing == null ? 0 : existing.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The applied credentials, encrypted at rest, or "" when none was recorded.
     *
     * Pre-upgrade builds kept this in plaintext in the suggestion prefs, first
     * as `ssid \0 pass` and then as `ssid \0 security \0 pass`. The first touch
     * migrates whichever shape is found into PassphraseStore and deletes the
     * plaintext copy. The hash-only record from the interim build is deleted
     * too: it cannot rebuild a suggestion.
     */
    private static String appliedRecord(Context c) {
        String stored = PassphraseStore.getSecret(c, SLOT_APPLIED);
        if (stored == null) stored = "";
        android.content.SharedPreferences store = prefs(c);
        String legacy = store.getString(KEY_LEGACY_APPLIED, null);
        if (legacy != null) {
            if (stored.isEmpty()) {
                String migrated = SuggestionRecord.migrate(legacy);
                if (!migrated.isEmpty()
                        && PassphraseStore.putSecret(c, SLOT_APPLIED, migrated)) {
                    stored = migrated;
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
        return stored;
    }

    /**
     * The suggestions this app has registered.
     *
     * `getNetworkSuggestions` only exists from Android 11, and on Android 10 it
     * is a `NoSuchMethodError`, not an exception the callers below would catch.
     * That version can only remove a suggestion by handing back an identical
     * object, so it is rebuilt from the record of what was actually added
     * (`applied`), not from the current form values: after a password change
     * those no longer match the suggestion Android holds. An empty record means
     * nothing was added, so `count()` reports 0 instead of claiming a
     * suggestion exists merely because a passphrase is stored.
     */
    private static List<WifiNetworkSuggestion> registered(WifiManager wm, String applied) {
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getNetworkSuggestions();
        }
        SuggestionRecord previous = SuggestionRecord.decode(applied);
        if (previous == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                suggest(previous.ssid(), previous.security(), previous.passphrase()));
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
}

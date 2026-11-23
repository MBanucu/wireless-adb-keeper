package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

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
    private static final String KEY_APPLIED = "applied_sha256";
    /** Pre-upgrade builds stored `ssid + "\0" + passphrase` under this key. */
    private static final String KEY_LEGACY_APPLIED = "applied";

    private WifiSuggestion() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int add(Context c) {
        dropLegacyApplied(c);
        WifiManager wm = manager(c);
        if (wm == null) return -1;

        String ssid = LocalWifi.ssid(c);
        String pass = LocalWifi.passphrase(c);
        if (pass == null || pass.isEmpty()) {
            Log.w(Ensurer.TAG, "wifi suggestion: no password stored");
            return -2;
        }

        // Re-adding an identical suggestion makes Android drop the network for a
        // moment, so only rewrite it when the configuration actually changed.
        String wanted = fingerprint(ssid, pass);
        String applied = prefs(c).getString(KEY_APPLIED, "");
        if (wanted.equals(applied) && count(c) > 0) {
            Log.i(Ensurer.TAG, "wifi suggestion already present for " + ssid);
            return STATUS_SUCCESS;
        }

        remove(c);

        WifiNetworkSuggestion suggestion;
        try {
            suggestion = suggest(ssid, pass);
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "wifi suggestion: build failed", e);
            return -3;
        }

        try {
            int status = wm.addNetworkSuggestions(Collections.singletonList(suggestion));
            Log.i(Ensurer.TAG, "wifi suggestion added for " + ssid + ": status " + status);
            if (status == STATUS_SUCCESS) {
                prefs(c).edit().putString(KEY_APPLIED, wanted).apply();
            }
            return status;
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "wifi suggestion: addNetworkSuggestions failed", e);
            return -4;
        }
    }

    public static void remove(Context c) {
        dropLegacyApplied(c);
        WifiManager wm = manager(c);
        if (wm == null) return;
        prefs(c).edit().remove(KEY_APPLIED).apply();
        try {
            List<WifiNetworkSuggestion> existing = registered(c, wm);
            if (existing != null && !existing.isEmpty()) {
                wm.removeNetworkSuggestions(existing);
                Log.i(Ensurer.TAG, "wifi suggestions removed: " + existing.size());
            }
        } catch (Exception e) {
            Log.w(Ensurer.TAG, "wifi suggestion: remove failed: " + e);
        }
    }

    public static int count(Context c) {
        WifiManager wm = manager(c);
        if (wm == null) return -1;
        try {
            List<WifiNetworkSuggestion> existing = registered(c, wm);
            return existing == null ? 0 : existing.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The suggestions this app has registered.
     *
     * `getNetworkSuggestions` only exists from Android 11, and on Android 10 it
     * is a `NoSuchMethodError`, not an exception the callers below would catch.
     * That version can only remove a suggestion by handing back an identical
     * object, so the one this app would have added is rebuilt from the stored
     * credentials instead.
     */
    private static List<WifiNetworkSuggestion> registered(Context c, WifiManager wm) {
        if (Build.VERSION.SDK_INT >= 30) {
            return wm.getNetworkSuggestions();
        }
        String pass = LocalWifi.passphrase(c);
        if (pass == null || pass.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(suggest(LocalWifi.ssid(c), pass));
    }

    /**
     * Identifies the applied credentials without keeping a second copy of the
     * secret. The SSID plus a SHA-256 of the passphrase is enough to notice a
     * change; a hash cannot be turned back into the passphrase.
     */
    private static String fingerprint(String ssid, String pass) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(pass.getBytes(StandardCharsets.UTF_8));
            return ssid + "\u0000" + Base64Codec.encode(digest);
        } catch (Exception e) {
            // SHA-256 always exists on Android; if it somehow does not, fall
            // back to the SSID alone and let the next pass re-add the network.
            Log.e(Ensurer.TAG, "wifi suggestion: no SHA-256 for change check", e);
            return ssid + "\u0000unavailable";
        }
    }

    /**
     * Pre-upgrade builds stored `ssid + "\0" + passphrase` under
     * {@link #KEY_LEGACY_APPLIED}. Remove that plaintext copy as soon as any
     * code path touches the suggestion; the suggestion is then re-added once
     * under the hash-only key.
     */
    private static void dropLegacyApplied(Context c) {
        if (prefs(c).contains(KEY_LEGACY_APPLIED)) {
            prefs(c).edit().remove(KEY_LEGACY_APPLIED).apply();
            Log.i(Ensurer.TAG, "wifi suggestion: removed legacy plaintext record");
        }
    }

    private static WifiNetworkSuggestion suggest(String ssid, String pass) {
        return new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .setIsMetered(true)
                .build();
    }

    private static WifiManager manager(Context c) {
        try {
            return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }
}

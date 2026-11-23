package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.util.Log;

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
    private static final String KEY_APPLIED = "applied";

    private WifiSuggestion() {
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int add(Context c) {
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
        String wanted = ssid + "\u0000" + pass;
        String applied = prefs(c).getString(KEY_APPLIED, "");
        if (wanted.equals(applied) && count(c) > 0) {
            Log.i(Ensurer.TAG, "wifi suggestion already present for " + ssid);
            return STATUS_SUCCESS;
        }

        remove(c);

        WifiNetworkSuggestion suggestion;
        try {
            suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .setIsMetered(true)
                    .build();
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
        WifiManager wm = manager(c);
        if (wm == null) return;
        prefs(c).edit().remove(KEY_APPLIED).apply();
        try {
            List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
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
            List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
            return existing == null ? 0 : existing.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static WifiManager manager(Context c) {
        try {
            return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }
}

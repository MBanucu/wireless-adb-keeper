package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.HashSet;
import java.util.Set;

/**
 * The Wi-Fi-on client order, without Android or libadb.
 *
 * <p>The regression this guards is the M3 switch: a Gradle install must try the
 * app's own trusted TLS key before Termux, while the dependency-free build and
 * an unpaired install keep their Termux fallback. Android 9 and older may still
 * use {@code WifiManager} directly.
 */
public final class WifiRouteTest {

    private WifiRouteTest() {
    }

    public static void main(String[] args) {
        theTlsKeyIsPreferredOverTermux();
        aBuildWithoutLibadbFallsBackToTermux();
        anUnpairedInstallWithoutTermuxFallsBackToThePanel();
        noClientLeavesOnlyThePanel();
        legacyAndroidTriesWifiManagerFirst();
        legacyAndroidStillFallsBack();
        everyClientHasADistinctJournalCode();
        report("WifiRouteTest");
    }

    private static void theTlsKeyIsPreferredOverTermux() {
        check("TLS, then Termux, then the panel",
                "APP_TLS,TERMUX,PANEL",
                names(WifiRoute.order(false, true, true)));
    }

    private static void aBuildWithoutLibadbFallsBackToTermux() {
        check("no libadb class means Termux first",
                "TERMUX,PANEL",
                names(WifiRoute.order(false, false, true)));
    }

    private static void anUnpairedInstallWithoutTermuxFallsBackToThePanel() {
        check("an unpaired app still offers the panel",
                "APP_TLS,PANEL",
                names(WifiRoute.order(false, true, false)));
    }

    private static void noClientLeavesOnlyThePanel() {
        check("no in-app key and no Termux leaves the panel",
                "PANEL",
                names(WifiRoute.order(false, false, false)));
    }

    private static void legacyAndroidTriesWifiManagerFirst() {
        check("Android 9 and older try WifiManager before TLS",
                "WIFI_MANAGER,APP_TLS,TERMUX,PANEL",
                names(WifiRoute.order(true, true, true)));
    }

    private static void legacyAndroidStillFallsBack() {
        check("a failed setWifiEnabled reaches the same fallbacks",
                "WIFI_MANAGER,TERMUX,PANEL",
                names(WifiRoute.order(true, false, true)));
    }

    private static void everyClientHasADistinctJournalCode() {
        Set<String> codes = new HashSet<>();
        for (WifiRoute.Client client : WifiRoute.Client.values()) {
            checkTrue("the client has a label: " + client, !client.label().isEmpty());
            checkTrue("the journal code names its area: " + client,
                    client.journalCode().matches("wifi\\.[a-z_]+"));
            codes.add(client.journalCode());
        }
        check("each client has its own journal code",
                WifiRoute.Client.values().length, codes.size());
        check("the TLS success code is stable", "wifi.tls_ok",
                WifiRoute.Client.APP_TLS.journalCode());
        check("the Termux success code is stable", "wifi.termux_asked",
                WifiRoute.Client.TERMUX.journalCode());
    }

    private static String names(WifiRoute.Client[] clients) {
        StringBuilder text = new StringBuilder();
        for (WifiRoute.Client client : clients) {
            if (text.length() > 0) text.append(',');
            text.append(client.name());
        }
        return text.toString();
    }
}

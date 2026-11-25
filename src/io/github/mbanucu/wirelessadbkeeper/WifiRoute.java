package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.List;

/**
 * The order {@link WifiRadio} tries the clients in, without Android.
 *
 * <p>The app's own trusted TLS key is preferred because it works from the
 * Gradle build without Termux. Termux stays as the fallback for an install
 * whose key is not paired and for the dependency-free build. Android 9 and
 * older can still toggle Wi-Fi directly, so that attempt comes first there.
 *
 * <p>This is Android-free so the decision can be tested on the JVM. The actual
 * attempts stay in {@link WifiRadio}; a failing client simply falls through to
 * the next entry in the order.
 */
public final class WifiRoute {
    /** A client that may switch the radio on, or the manual panel. */
    public enum Client {
        WIFI_MANAGER("Wi-FiManager", "wifi.manager_ok"),
        APP_TLS("app_tls", "wifi.tls_ok"),
        TERMUX("termux", "wifi.termux_asked"),
        PANEL("panel", "wifi.panel");

        private final String label;
        private final String journalCode;

        Client(String label, String journalCode) {
            this.label = label;
            this.journalCode = journalCode;
        }

        /** The client name shown in diagnostics and the verbose log. */
        public String label() {
            return label;
        }

        /** The event written when this client takes the request. */
        public String journalCode() {
            return journalCode;
        }
    }

    private WifiRoute() {}

    /**
     * The clients to try, in order.
     *
     * @param legacySdk true when {@code WifiManager.setWifiEnabled} still works
     * @param tlsPresent true when the Gradle-only in-app TLS class is loaded
     * @param termuxUsable true when Termux is installed and can run commands
     */
    public static Client[] order(boolean legacySdk, boolean tlsPresent, boolean termuxUsable) {
        List<Client> clients = new ArrayList<>(4);
        if (legacySdk) clients.add(Client.WIFI_MANAGER);
        if (tlsPresent) clients.add(Client.APP_TLS);
        if (termuxUsable) clients.add(Client.TERMUX);
        clients.add(Client.PANEL);
        return clients.toArray(new Client[0]);
    }
}

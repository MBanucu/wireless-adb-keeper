package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

/**
 * The in-app Wi-Fi transport: runs {@code svc wifi enable} through the app's
 * trusted TLS connection.
 *
 * <p>This lives in {@code src-pairing} because only the Gradle build has libadb.
 * {@link WifiRadio} loads it by name, so the dependency-free build still
 * compiles and simply keeps the Termux fallback.
 */
public final class TlsWifiTransport implements WifiTransport {
    private final Context context;

    public TlsWifiTransport(Context context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "tls";
    }

    @Override
    public boolean enable() {
        return Pairing.enableWifi(context);
    }
}

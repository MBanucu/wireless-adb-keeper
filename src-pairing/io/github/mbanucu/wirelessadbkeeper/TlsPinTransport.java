package io.github.mbanucu.wirelessadbkeeper;

import android.content.Context;

/**
 * The in-app pin transport: asks adbd over the app's trusted TLS connection.
 *
 * <p>This lives in {@code src-pairing} because only the Gradle build has libadb.
 * {@link PinnedPort} loads it by name, so the dependency-free build still
 * compiles and simply keeps the Termux fallback.
 */
public final class TlsPinTransport implements PinTransport {
    private final Context context;

    public TlsPinTransport(Context context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "tls";
    }

    @Override
    public boolean request(int port) {
        return Pairing.pinPort(context, port);
    }
}

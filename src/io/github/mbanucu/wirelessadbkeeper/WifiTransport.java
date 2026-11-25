package io.github.mbanucu.wirelessadbkeeper;

/**
 * One way to ask the platform to switch the Wi-Fi radio on. {@link WifiRadio}
 * owns which transport to try and in what order; a transport owns how.
 *
 * <p>The in-app implementation ({@code TlsWifiTransport}, Gradle build only)
 * opens the app's trusted TLS connection and sends
 * {@code shell:svc wifi enable}. Termux remains the fallback for an unpaired
 * install and for the dependency-free build: its adb client already holds a
 * key adbd trusts, so the app sends it a {@code RUN_COMMAND} intent. The seam
 * keeps {@code src/} free of libadb, exactly like {@link PinTransport}.
 */
public interface WifiTransport {
    /** Short name for the verbose log and diagnostics. */
    String name();

    /**
     * Sends {@code svc wifi enable}.
     *
     * @return true when the request left the app, false when it could not
     */
    boolean enable();
}

package io.github.mbanucu.wirelessadbkeeper;

/**
 * One way to ask adbd to listen on the fixed TCP port, i.e. to send
 * {@code tcpip:5555}. {@link PinnedPort} owns when to ask; a transport owns how.
 *
 * <p>The in-app implementation ({@code TlsPinTransport}, Gradle build only)
 * uses the app's own paired key over TLS. Termux remains the fallback: its adb
 * client already holds a key adbd trusts, so the app sends it a
 * {@code RUN_COMMAND} intent. The in-app transport is tried first when it is
 * present, and Termux is kept as the unpaired-install fallback.
 *
 * <p>{@code tcpip} tears down the transport that sent it; the in-app
 * implementation closes its connection and lets the next port probe confirm
 * the new listener. This seam carries only the request and its success or
 * refusal.
 */
public interface PinTransport {
    /** Short name for the verbose log and diagnostics. */
    String name();

    /**
     * Sends {@code tcpip:<port>}.
     *
     * @return true when the request left the app, false when it was refused
     */
    boolean request(int port);
}

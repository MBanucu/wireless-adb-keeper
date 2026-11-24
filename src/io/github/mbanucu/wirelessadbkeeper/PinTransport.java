package io.github.mbanucu.wirelessadbkeeper;

/**
 * One way to ask adbd to listen on the fixed TCP port, i.e. to send
 * {@code tcpip:5555}. {@link PinnedPort} owns when to ask; a transport owns how.
 *
 * <p>Today the only implementation is the Termux fallback: Termux's adb client
 * already holds a key adbd trusts, so the app sends it a {@code RUN_COMMAND}
 * intent. The in-app path (#5) needs the app's own key from #4 and the TLS
 * transport tracked there. When it lands it implements this interface and is
 * tried first, with Termux kept as the unpaired-install fallback. No TLS client
 * and no fake transport are implemented here.
 *
 * <p>{@code tcpip} tears down the transport that sent it, so a transport that
 * reconnects to rediscover the moved TLS port belongs to #5; this seam only
 * carries the request and its success or refusal.
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

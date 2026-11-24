package io.github.mbanucu.wirelessadbkeeper;

/**
 * The boot, Wi-Fi and unlock gates in front of the fixed-port pin, decided
 * without Android.
 *
 * <p>A service started at {@code LOCKED_BOOT_COMPLETED} runs before the first
 * unlock and before Wi-Fi is guaranteed to be associated: the boot journal
 * shows {@code ensure.no_wifi} at 27 s and the association at ~28 s, so a fixed
 * delay is either too early or too late. The first attempt therefore waits for
 * {@code ConnectivityManager} to report a Wi-Fi network, and the existing 10 s
 * tick continues after that.
 *
 * <p>Unlock is a separate gate. Termux's home is credential-encrypted storage,
 * so a {@code RUN_COMMAND} before {@code ACTION_USER_UNLOCKED} is deferred until
 * the unlock and arrives too late to be useful. The in-app TLS transport is
 * different: its key is in device-protected storage, so it may ask while the
 * user is locked. The port is always probed (an already-listening 5555 is worth
 * reporting); a closed port with no successful in-app pin journals
 * {@code pin.blocked_locked} instead of {@code pin.waiting}. The unlock forces
 * one immediate check.
 *
 * <p>The gate is a plain object so a JVM test can replay a locked boot, a
 * mid-boot unlock and a Wi-Fi association in either order.
 */
public final class PinGate {
    /** No Wi-Fi network has been reported since this service started. */
    public static final String WAIT_FOR_WIFI = "pin.wait_for_wifi";
    /** The port is closed but the user is locked, so Termux cannot be asked. */
    public static final String BLOCKED_LOCKED = "pin.blocked_locked";

    /** What the gate currently waits for. */
    public enum State {
        /** Started before unlock and no Wi-Fi network yet; do not probe. */
        WAITING_FOR_WIFI(WAIT_FOR_WIFI),
        /** Wi-Fi is up, but the user is locked; probe, only in-app TLS may ask. */
        WAITING_FOR_UNLOCK(BLOCKED_LOCKED),
        /** The user is unlocked; probe and ask the transport. */
        READY("");

        private final String code;

        State(String code) {
            this.code = code;
        }

        /** The journal code for this state; empty when nothing waits. */
        public String code() {
            return code;
        }
    }

    private boolean wifiReady;
    private boolean unlocked;

    private PinGate(boolean wifiReady, boolean unlocked) {
        this.wifiReady = wifiReady;
        this.unlocked = unlocked;
    }

    /**
     * The gate for a monitor service starting now. An unlocked start has always
     * probed immediately; a locked start waits for the first Wi-Fi network
     * because the boot broadcast is not a network event.
     */
    public static PinGate atServiceStart(boolean unlocked) {
        return new PinGate(unlocked, unlocked);
    }

    /**
     * Termux cannot be asked before the first unlock: its home and its adb
     * state live in credential-encrypted storage, so the request would be
     * deferred until the unlock. The current in-app transport (#5) will not have
     * this limit because its key is device-protected.
     */
    public static boolean termuxAllowed(boolean unlocked) {
        return unlocked;
    }

    /** The journal sentence for a closed port after the in-app TLS try. */
    public static String lockedDetail() {
        return "the user is locked and the in-app TLS pin did not take; Termux is not"
                + " asked until ACTION_USER_UNLOCKED because its home is credential-encrypted";
    }

    /**
     * ConnectivityManager reported a Wi-Fi network. The gate stays open from
     * here: the 10 s tick keeps probing even if the network goes away, and a
     * later {@code tcpip} can still be attempted.
     */
    public void onWifiAvailable() {
        wifiReady = true;
    }

    /** ACTION_USER_UNLOCKED, or any later evidence that storage is readable. */
    public void onUserUnlocked() {
        unlocked = true;
    }

    /** Whether no Wi-Fi network has been reported since a locked start. */
    public boolean waitsForWifi() {
        return !wifiReady;
    }

    /** Whether the port probe may run at all; a locked boot waits for Wi-Fi first. */
    public boolean allowsAttempt() {
        return wifiReady;
    }

    /** Whether Termux may be asked; in-app TLS is available separately. */
    public boolean allowsTransport() {
        return unlocked;
    }

    public State state() {
        if (!wifiReady) return State.WAITING_FOR_WIFI;
        if (!unlocked) return State.WAITING_FOR_UNLOCK;
        return State.READY;
    }

    /** One sentence for the journal, or empty when the gate is open. */
    public String detail() {
        switch (state()) {
            case WAITING_FOR_WIFI:
                return "started before unlock and no Wi-Fi network is up yet;"
                        + " the first pin attempt waits for ConnectivityManager";
            case WAITING_FOR_UNLOCK:
                return lockedDetail();
            case READY:
            default:
                return "";
        }
    }
}

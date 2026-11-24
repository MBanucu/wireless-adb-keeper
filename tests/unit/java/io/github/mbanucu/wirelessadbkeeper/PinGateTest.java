package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

/**
 * The boot/Wi-Fi/unlock gate for the fixed-port pin, without Android.
 *
 * <p>The regression this covers is #100: a service started at
 * {@code LOCKED_BOOT_COMPLETED} must not probe before a Wi-Fi network exists,
 * and must not ask Termux until the first unlock. The gate is replayed in both
 * orders (association then unlock, and unlock then association) because the
 * boot journal shows only one second between them.
 */
public final class PinGateTest {

    private PinGateTest() {
    }

    public static void main(String[] args) {
        aLockedBootWaitsForWifiBeforeTheFirstAttempt();
        wifiWhileLockedAllowsTheProbeButNotTermux();
        unlockAfterWifiOpensTheGate();
        anUnlockBeforeWifiStillWaitsForTheNetwork();
        anUnlockedStartProbesWithoutWaiting();
        theTermuxLockGateOnlyOpensAfterUnlock();
        repeatedEventsLeaveTheGateWhereItWas();
        report("PinGateTest");
    }

    private static void aLockedBootWaitsForWifiBeforeTheFirstAttempt() {
        PinGate gate = PinGate.atServiceStart(false);

        check("a locked boot waits for Wi-Fi", PinGate.State.WAITING_FOR_WIFI,
                gate.state());
        check("it journals the Wi-Fi wait", PinGate.WAIT_FOR_WIFI, gate.state().code());
        check("it does not probe before a network exists", false, gate.allowsAttempt());
        check("it does not ask Termux before unlock", false, gate.allowsTransport());
        checkTrue("it is waiting for a network", gate.waitsForWifi());
        checkTrue("the detail names ConnectivityManager",
                gate.detail().contains("ConnectivityManager"));
        checkTrue("the detail does not claim a pin happened",
                !gate.detail().contains("pinned"));
    }

    private static void wifiWhileLockedAllowsTheProbeButNotTermux() {
        PinGate gate = PinGate.atServiceStart(false);
        gate.onWifiAvailable();

        check("Wi-Fi lets the probe run", true, gate.allowsAttempt());
        check("it still waits for unlock", PinGate.State.WAITING_FOR_UNLOCK,
                gate.state());
        check("a closed port journals blocked_locked", PinGate.BLOCKED_LOCKED,
                gate.state().code());
        check("Termux is still refused", false, gate.allowsTransport());
        check("it no longer waits for Wi-Fi", false, gate.waitsForWifi());
        checkTrue("the detail says Termux waits for the unlock",
                gate.detail().contains("ACTION_USER_UNLOCKED"));
    }

    private static void unlockAfterWifiOpensTheGate() {
        PinGate gate = PinGate.atServiceStart(false);
        gate.onWifiAvailable();
        gate.onUserUnlocked();

        check("the unlock opens the gate", PinGate.State.READY, gate.state());
        check("an open gate journals nothing", "", gate.state().code());
        check("the probe is allowed", true, gate.allowsAttempt());
        check("the transport is allowed", true, gate.allowsTransport());
        check("the detail is empty when ready", "", gate.detail());
    }

    private static void anUnlockBeforeWifiStillWaitsForTheNetwork() {
        PinGate gate = PinGate.atServiceStart(false);
        gate.onUserUnlocked();

        // ACTION_USER_UNLOCKED forces a check, but the first attempt still waits
        // for the association; the network callback starts it.
        check("an early unlock still waits for Wi-Fi", PinGate.State.WAITING_FOR_WIFI,
                gate.state());
        check("the probe stays gated", false, gate.allowsAttempt());
        check("the transport is open but unused", true, gate.allowsTransport());

        gate.onWifiAvailable();
        check("the later association opens the gate", PinGate.State.READY, gate.state());
        check("the probe is allowed after the association", true,
                gate.allowsAttempt());
    }

    private static void anUnlockedStartProbesWithoutWaiting() {
        PinGate gate = PinGate.atServiceStart(true);

        // An already-running monitor has always probed immediately; only the
        // LOCKED_BOOT_COMPLETED start gained the Wi-Fi wait.
        check("an unlocked start is ready", PinGate.State.READY, gate.state());
        check("it does not wait for Wi-Fi", false, gate.waitsForWifi());
        check("it may probe", true, gate.allowsAttempt());
        check("it may ask Termux", true, gate.allowsTransport());
    }

    private static void theTermuxLockGateOnlyOpensAfterUnlock() {
        check("Termux is not asked before unlock", false, PinGate.termuxAllowed(false));
        check("Termux is the fallback after unlock", true, PinGate.termuxAllowed(true));
        checkTrue("the locked detail names the broadcast",
                PinGate.lockedDetail().contains("ACTION_USER_UNLOCKED"));
        checkTrue("the locked detail says why",
                PinGate.lockedDetail().contains("credential-encrypted"));
    }

    private static void repeatedEventsLeaveTheGateWhereItWas() {
        PinGate gate = PinGate.atServiceStart(false);
        gate.onWifiAvailable();
        gate.onWifiAvailable();
        check("a repeated network event does not close the gate",
                PinGate.State.WAITING_FOR_UNLOCK, gate.state());
        gate.onUserUnlocked();
        gate.onUserUnlocked();
        check("a repeated unlock leaves the gate ready", PinGate.State.READY,
                gate.state());
        check("the ready state has no journal code", "", gate.state().code());
    }
}

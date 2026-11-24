package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

/**
 * The DokeOS autostart state must follow the observed boot delivery, never a
 * guess: a journal that cannot prove the ROM delivered the broadcast before
 * unlock is {@code unknown}, and only an after-unlock delivery with a late
 * process start is {@code blocked}.
 */
public final class OemAutostartTest {

    /** 2026-11-24T13:30:00Z, the field report's day. */
    private static final long NOW = 1763991000000L;

    private OemAutostartTest() {
    }

    public static void main(String[] args) {
        OemAutostart.Result notVendor = classify(false, "", 40, 40);
        check("a phone without the vendor manager is unknown", OemAutostart.UNKNOWN,
                notVendor.state());
        checkTrue("the non-vendor reason says nothing was detected",
                notVendor.reason().contains("no DokeOS autostart manager"));

        OemAutostart.Result empty = classify(true, "", 40, 40);
        check("a vendor phone with no boot event is unknown", OemAutostart.UNKNOWN,
                empty.state());
        checkTrue("the empty reason names the screen to check",
                empty.reason().contains(OemAutostart.SCREEN));

        // Allowlisted: LOCKED_BOOT_COMPLETED arrived 27 s in, before unlock.
        OemAutostart.Result allowed = classify(true,
                bootLine(40, 27, "android.intent.action.LOCKED_BOOT_COMPLETED"
                        + " before unlock"), 40, 27);
        check("a pre-unlock boot broadcast is allowed", OemAutostart.ALLOWED,
                allowed.state());
        checkTrue("the allowed reason names the delivery and its uptime",
                allowed.reason().contains("before unlock")
                        && allowed.reason().contains("27 s"));
        checkTrue("the snapshot value carries the state and reason",
                allowed.line().startsWith("allowed (boot.received before unlock"));

        // Blocked: the ROM queued the broadcast until the app was opened at 238 s.
        OemAutostart.Result blocked = classify(true,
                bootLine(260, 238, "android.intent.action.BOOT_COMPLETED after unlock"),
                260, 238);
        check("a late after-unlock delivery is blocked", OemAutostart.BLOCKED,
                blocked.state());
        checkTrue("the blocked reason names the screen to fix",
                blocked.reason().contains(OemAutostart.SCREEN));
        checkTrue("the blocked reason keeps the measured uptimes",
                blocked.reason().contains("after unlock at 238 s")
                        && blocked.reason().contains("process started at 238 s"));
        checkTrue("the blocked reason never claims the vendor list was read",
                !blocked.reason().contains("list says"));

        // An early unlock is not evidence: do not accuse the ROM.
        OemAutostart.Result early = classify(true,
                bootLine(25, 20, "android.intent.action.BOOT_COMPLETED after unlock"),
                25, 20);
        check("an early after-unlock delivery stays unknown", OemAutostart.UNKNOWN,
                early.state());
        checkTrue("the unknown reason explains why it is not proof",
                early.reason().contains("not proof either way"));

        // The journal can outlive a reboot. A previous boot's before-unlock line
        // must not turn this boot's late delivery into allowed.
        long previous = NOW - 3600_000L;
        String stale = DiagRetention.time(previous) + " 27s info boot.received"
                + " android.intent.action.LOCKED_BOOT_COMPLETED before unlock\n"
                + bootLine(260, 238, "android.intent.action.BOOT_COMPLETED after unlock");
        OemAutostart.Result mixed = classify(true, stale, 260, 238);
        check("a stale pre-unlock line does not hide this boot's block",
                OemAutostart.BLOCKED, mixed.state());

        // Only a previous boot's line: this boot has no evidence at all.
        OemAutostart.Result onlyStale = classify(true,
                DiagRetention.time(previous) + " 27s info boot.received"
                        + " android.intent.action.LOCKED_BOOT_COMPLETED before unlock",
                40, 27);
        check("only a previous-boot line is unknown, not allowed", OemAutostart.UNKNOWN,
                onlyStale.state());

        // An uptime larger than the current one cannot belong to this boot.
        OemAutostart.Result future = classify(true,
                bootLine(40, 900, "android.intent.action.BOOT_COMPLETED after unlock"),
                40, 27);
        check("a boot line after this process is ignored", OemAutostart.UNKNOWN,
                future.state());

        // A first boot event with no unlock word is not usable evidence.
        OemAutostart.Result unmarked = classify(true,
                bootLine(40, 27, "android.intent.action.LOCKED_BOOT_COMPLETED"),
                40, 27);
        check("a boot line without before/after is unknown", OemAutostart.UNKNOWN,
                unmarked.state());

        OemAutostart.Result malformed = classify(true,
                "not a journal line\n" + bootLine(40, 27,
                        "android.intent.action.LOCKED_BOOT_COMPLETED before unlock"),
                40, 27);
        check("a malformed neighbour does not hide the boot line",
                OemAutostart.ALLOWED, malformed.state());

        checkTrue("the late-start threshold is above the measured healthy start",
                OemAutostart.LATE_PROCESS_START_S > 30L);
        checkTrue("the late-start threshold is below the measured blocked start",
                OemAutostart.LATE_PROCESS_START_S < 238L);

        report("OemAutostartTest");
    }

    private static OemAutostart.Result classify(boolean vendor, String journal,
                                                long uptimeS, long processStartedS) {
        return OemAutostart.classify(new OemAutostart.Inputs(
                vendor, journal, NOW, uptimeS, processStartedS));
    }

    /** One {@code boot.received} line at {@code eventUptimeS} of a boot now at {@code uptimeS}. */
    private static String bootLine(long uptimeS, long eventUptimeS, String detail) {
        long bootStart = NOW - uptimeS * 1000L;
        return DiagRetention.time(bootStart + eventUptimeS * 1000L)
                + " " + eventUptimeS + "s info boot.received " + detail;
    }
}

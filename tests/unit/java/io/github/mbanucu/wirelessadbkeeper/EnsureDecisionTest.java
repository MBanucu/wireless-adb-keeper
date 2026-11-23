package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import io.github.mbanucu.wirelessadbkeeper.EnsureDecision.Reason;

/**
 * The ensure reason table, in the order the app has always decided it:
 *
 * <pre>
 * 1  no WRITE_SECURE_SETTINGS           ensure.no_permission  warn
 * 2  Wi-Fi not connected                ensure.no_wifi        info
 * 3  the settings read/write threw      ensure.failed         error
 * 4  wireless debugging is off          ensure.changed        info
 * 5  wireless debugging is already on   ensure.noop           info
 * </pre>
 *
 * <p>Permission wins over Wi-Fi, and both win over an unreadable setting. A
 * developer-settings write happens on the way but does not change the reason.
 */
public final class EnsureDecisionTest {

    private EnsureDecisionTest() {
    }

    public static void main(String[] args) {
        check("every reason has a journal code", 6, Reason.values().length);

        check("no permission is ensure.no_permission", Reason.NO_PERMISSION,
                decide(false, true, true, true, true));
        check("no permission records at warn", "warn", Reason.NO_PERMISSION.level());
        check("no permission carries its exact journal code", "ensure.no_permission",
                Reason.NO_PERMISSION.code());
        check("no permission carries its exact result", "Missing WRITE_SECURE_SETTINGS permission",
                Reason.NO_PERMISSION.result());

        check("no Wi-Fi is ensure.no_wifi", Reason.NO_WIFI,
                decide(true, false, true, true, true));
        check("no Wi-Fi carries its exact journal code", "ensure.no_wifi",
                Reason.NO_WIFI.code());
        check("no Wi-Fi carries its exact result", "Wi-Fi not connected",
                Reason.NO_WIFI.result());

        check("an unreadable setting is ensure.failed", Reason.FAILED,
                decide(true, true, false, true, true));
        check("a failure records at error", "error", Reason.FAILED.level());
        check("a failure carries its exact journal code", "ensure.failed",
                Reason.FAILED.code());

        check("a refused write is ensure.settings_refused", "ensure.settings_refused",
                Reason.SETTINGS_REFUSED.code());
        check("a refused write records at error", "error", Reason.SETTINGS_REFUSED.level());
        check("a refused write carries its exact result", "Settings write refused",
                Reason.SETTINGS_REFUSED.result());

        check("wireless debugging off is ensure.changed", Reason.CHANGED,
                decide(true, true, true, true, false));
        check("changed carries its exact journal code", "ensure.changed",
                Reason.CHANGED.code());
        check("changed carries its exact result", "Enabled wireless debugging",
                Reason.CHANGED.result());

        check("wireless debugging on is ensure.noop", Reason.NOOP,
                decide(true, true, true, true, true));
        check("noop records at info", "info", Reason.NOOP.level());
        check("noop carries its exact journal code", "ensure.noop", Reason.NOOP.code());
        check("noop carries its exact result", "Already enabled", Reason.NOOP.result());

        check("permission is decided before Wi-Fi", Reason.NO_PERMISSION,
                decide(false, false, false, false, false));
        check("Wi-Fi is decided before an unreadable setting", Reason.NO_WIFI,
                decide(true, false, false, false, false));
        check("an unreadable setting is decided before the value", Reason.FAILED,
                decide(true, true, false, false, false));
        check("developer settings alone do not change the reason", Reason.NOOP,
                decide(true, true, true, false, true));
        check("developer settings off while debugging is off is still changed", Reason.CHANGED,
                decide(true, true, true, false, false));

        report("EnsureDecisionTest");
    }

    private static Reason decide(boolean permission, boolean wifi, boolean readable,
                                 boolean developerSettings, boolean debugging) {
        return EnsureDecision.decide(
                new EnsureDecision.Inputs(permission, wifi, readable, developerSettings, debugging));
    }
}

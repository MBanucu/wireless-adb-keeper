package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.HashMap;
import java.util.Map;

/**
 * Control-call argument resolution, including the {@code mode local} toggle bug.
 *
 * <p>The old provider read the mode only from the extras bundle. `keeper.sh
 * mode local` passes it positionally, so the old code saw null and
 * {@link Keeper#setMode} toggled: asking for the mode it was already in switched
 * it to the other one. The first check below is that old rule, kept as the
 * failing case; the next one is the fixed rule the provider now uses.
 */
public final class ControlArgsTest {

    private ControlArgsTest() {
    }

    public static void main(String[] args) {
        check("the old extras-only rule leaves the positional mode null (and toggles)",
                null, oldModeOf("set_mode", extras()));

        ControlArgs first = ControlArgs.of("set_mode", "local", extras());
        check("set_mode takes its mode from the positional argument", "local", first.mode);
        check("mode local is the SET_MODE command", ControlArgs.Method.SET_MODE, first.method);
        ControlArgs second = ControlArgs.of("set_mode", "local", extras());
        check("mode local twice stays local, not toggled to auto", "local", second.mode);
        check("an extras mode still wins over the positional argument", "auto",
                ControlArgs.of("set_mode", "local", extras("mode", "auto")).mode);

        ControlArgs save = ControlArgs.of("save_wifi", "Home", extras());
        check("save_wifi takes its SSID from the positional argument", "Home", save.ssid);
        check("save_wifi does not treat the SSID as a mode", null, save.mode);
        check("save_wifi's password travels only as an extra", "secret",
                ControlArgs.of("save_wifi", "Home", extras("passphrase", "secret")).passphrase);
        check("an extras SSID still wins over the positional argument", "Other",
                ControlArgs.of("save_wifi", "Home", extras("ssid", "Other")).ssid);

        ControlArgs missingSsid = ControlArgs.of("save_wifi", null, extras());
        check("save_wifi without an SSID resolves a null SSID", null, missingSsid.ssid);
        check("save_wifi without an SSID is still the SAVE_WIFI command",
                ControlArgs.Method.SAVE_WIFI, missingSsid.method);

        ControlArgs unknown = ControlArgs.of("frobnicate", null, extras());
        check("an unknown method is typed UNKNOWN", ControlArgs.Method.UNKNOWN, unknown.method);
        check("an unknown method is not known", false, unknown.known());
        check("an unknown method keeps its wire name", "frobnicate", unknown.name);
        check("an absent method is UNKNOWN", ControlArgs.Method.UNKNOWN,
                ControlArgs.of(null, null, extras()).method);

        check("status is typed STATUS", ControlArgs.Method.STATUS,
                ControlArgs.of("status", null, null).method);
        check("enable is typed ENABLE", ControlArgs.Method.ENABLE,
                ControlArgs.of("enable", null, null).method);
        check("wifi_on is typed WIFI_ON", ControlArgs.Method.WIFI_ON,
                ControlArgs.of("wifi_on", null, null).method);
        check("service_start is typed SERVICE_START", ControlArgs.Method.SERVICE_START,
                ControlArgs.of("service_start", null, null).method);
        check("service_stop is typed SERVICE_STOP", ControlArgs.Method.SERVICE_STOP,
                ControlArgs.of("service_stop", null, null).method);
        check("connect is typed CONNECT", ControlArgs.Method.CONNECT,
                ControlArgs.of("connect", null, null).method);
        check("pin_port is typed PIN_PORT", ControlArgs.Method.PIN_PORT,
                ControlArgs.of("pin_port", null, null).method);
        check("pubkey is typed PUBKEY", ControlArgs.Method.PUBKEY,
                ControlArgs.of("pubkey", null, null).method);
        check("diag is typed DIAG", ControlArgs.Method.DIAG,
                ControlArgs.of("diag", null, null).method);
        check("schedule is typed SCHEDULE", ControlArgs.Method.SCHEDULE,
                ControlArgs.of("schedule", null, null).method);
        check("token is typed TOKEN", ControlArgs.Method.TOKEN,
                ControlArgs.of("token", null, null).method);
        checkTrue("a known method reports itself known", first.known());

        report("ControlArgsTest");
    }

    /** The pre-fix rule: only the extras bundle was consulted for the mode. */
    private static String oldModeOf(String method, ControlArgs.Extras extras) {
        return extras.get("mode");
    }

    private static ControlArgs.Extras extras(String... keysAndValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map::get;
    }
}

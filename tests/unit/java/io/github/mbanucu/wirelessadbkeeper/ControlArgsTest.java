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
        check("save_wifi without an explicit profile has no id", null, save.id);

        ControlArgs saveProfile = ControlArgs.of("save_wifi", "Home",
                extras("id", "p2", "passphrase", "secret"));
        check("save_wifi carries an explicit profile id as an extra", "p2", saveProfile.id);
        check("an explicit save still takes the SSID positionally", "Home",
                saveProfile.ssid);
        check("an explicit save still carries the password", "secret",
                saveProfile.passphrase);

        ControlArgs remove = ControlArgs.of("remove_profile", "p2", extras());
        check("remove_profile is typed REMOVE_PROFILE", ControlArgs.Method.REMOVE_PROFILE,
                remove.method);
        check("remove_profile takes its id from the positional argument", "p2", remove.id);
        check("remove_profile does not treat the id as an SSID", null, remove.ssid);
        check("an extras id wins for remove_profile", "p3",
                ControlArgs.of("remove_profile", "p2", extras("id", "p3")).id);
        check("remove_profile without an id resolves null", null,
                ControlArgs.of("remove_profile", null, extras()).id);

        ControlArgs priority = ControlArgs.of("set_priority", "p2", extras("priority", "3"));
        check("set_priority is typed SET_PRIORITY", ControlArgs.Method.SET_PRIORITY,
                priority.method);
        check("set_priority takes its id from the positional argument", "p2", priority.id);
        check("set_priority takes the priority from an extra", "3", priority.priority);
        check("set_priority without a priority resolves null", null,
                ControlArgs.of("set_priority", "p2", extras()).priority);

        check("profiles is typed PROFILES", ControlArgs.Method.PROFILES,
                ControlArgs.of("profiles", null, null).method);

        ControlArgs autostart = ControlArgs.of("autostart", null, extras());
        check("autostart is typed AUTOSTART", ControlArgs.Method.AUTOSTART, autostart.method);
        check("autostart without a label targets this app", null, autostart.label);
        check("autostart takes its label from the positional argument", "Custom App",
                ControlArgs.of("autostart", "Custom App", extras()).label);
        check("an extras label wins for autostart", "Other App",
                ControlArgs.of("autostart", "Custom App", extras("label", "Other App")).label);
        check("a label is not mistaken for an SSID", null, autostart.ssid);

        ControlArgs missingSsid = ControlArgs.of("save_wifi", null, extras());
        check("save_wifi without an SSID resolves a null SSID", null, missingSsid.ssid);
        check("save_wifi without an SSID is still the SAVE_WIFI command",
                ControlArgs.Method.SAVE_WIFI, missingSsid.method);

        ControlArgs pair = ControlArgs.of("pair", null, extras("code", "123456"));
        check("pair is typed PAIR", ControlArgs.Method.PAIR, pair.method);
        check("pair takes the code from the code extra", "123456", pair.code);
        check("pair does not treat the code as an SSID", null, pair.ssid);
        check("an extras code wins for pair", "654321",
                ControlArgs.of("pair", "123456", extras("code", "654321")).code);
        check("pair takes the code positionally too", "111111",
                ControlArgs.of("pair", "111111", extras()).code);
        check("pair without a code resolves null", null,
                ControlArgs.of("pair", null, extras()).code);
        ControlArgs pairVerify = ControlArgs.of("pair_verify", null, extras());
        check("pair_verify is typed PAIR_VERIFY", ControlArgs.Method.PAIR_VERIFY,
                pairVerify.method);
        check("pair_verify carries no code", null, pairVerify.code);

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

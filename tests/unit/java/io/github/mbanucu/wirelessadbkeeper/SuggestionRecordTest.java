package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

/**
 * The applied suggestion record. The legacy two-field shape is the one that
 * broke removal after the security field was added: it has to migrate to WPA2
 * plus a three-field record, not be read as an empty security.
 */
public final class SuggestionRecordTest {

    private SuggestionRecordTest() {
    }

    public static void main(String[] args) {
        String encoded = SuggestionRecord.encode("Home", "wpa2", "secret");
        check("encode joins the three fields with the record separator",
                "Home\u0000wpa2\u0000secret", encoded);

        SuggestionRecord decoded = SuggestionRecord.decode(encoded);
        check("a current record round-trips the SSID", "Home", decoded.ssid());
        check("a current record round-trips the security", "wpa2", decoded.security());
        check("a current record round-trips the passphrase", "secret", decoded.passphrase());
        check("re-encoding a decoded record is byte-identical", encoded, decoded.encode());

        SuggestionRecord unicode = SuggestionRecord.decode(
                SuggestionRecord.encode("Café-网络-📶", "wpa3", "pässwörd"));
        check("a unicode SSID survives the record", "Café-网络-📶", unicode.ssid());
        check("a unicode passphrase survives the record", "pässwörd", unicode.passphrase());

        SuggestionRecord legacy = SuggestionRecord.decode("Home\u0000secret");
        check("a legacy two-field record keeps its SSID", "Home", ssid(legacy));
        check("a legacy two-field record migrates to WPA2", "wpa2", security(legacy));
        check("a legacy two-field record keeps its passphrase", "secret", passphrase(legacy));
        check("the legacy record migrates to the three-field shape",
                "Home\u0000wpa2\u0000secret", SuggestionRecord.migrate("Home\u0000secret"));
        check("the legacy migration re-encodes from the decoded record",
                legacy == null ? null : legacy.encode(),
                SuggestionRecord.migrate("Home\u0000secret"));

        SuggestionRecord unknown = SuggestionRecord.decode("Home\u0000wep\u0000secret");
        check("an unknown security migrates to WPA2", "wpa2", security(unknown));
        check("an empty security migrates to WPA2", "wpa2",
                security(SuggestionRecord.decode("Home\u0000\u0000secret")));
        check("a known security is kept", "owe",
                security(SuggestionRecord.decode("Home\u0000owe\u0000")));

        SuggestionRecord extraSeparator = SuggestionRecord.decode("Home\u0000wpa2\u0000a\u0000b");
        check("separators inside the passphrase do not start a new field",
                "a\u0000b", passphrase(extraSeparator));
        check("the record with separators inside its passphrase re-encodes as one record",
                "Home\u0000wpa2\u0000a\u0000b",
                extraSeparator == null ? null : extraSeparator.encode());

        check("an absent record decodes to nothing", null, SuggestionRecord.decode(null));
        check("an empty record decodes to nothing", null, SuggestionRecord.decode(""));
        check("a record without a separator decodes to nothing", null,
                SuggestionRecord.decode("Home"));
        check("an absent record migrates to nothing", "", SuggestionRecord.migrate(null));
        check("a malformed record migrates to nothing", "", SuggestionRecord.migrate("Home"));
        checkTrue("a two-field record is not treated as malformed",
                !SuggestionRecord.migrate("Home\u0000secret").isEmpty());

        report("SuggestionRecordTest");
    }

    private static String ssid(SuggestionRecord record) {
        return record == null ? "<no record>" : record.ssid();
    }

    private static String security(SuggestionRecord record) {
        return record == null ? "<no record>" : record.security();
    }

    private static String passphrase(SuggestionRecord record) {
        return record == null ? "<no record>" : record.passphrase();
    }
}

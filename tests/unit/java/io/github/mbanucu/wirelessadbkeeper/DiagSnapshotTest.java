package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.check;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.checkTrue;
import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The status snapshot is assembled from independent fields. One unreadable
 * field is rendered {@code unavailable}, never dropped: before the first unlock
 * the Wi-Fi prefs are exactly the field the page exists to explain.
 */
public final class DiagSnapshotTest {

    private DiagSnapshotTest() {
    }

    public static void main(String[] args) {
        List<DiagSnapshot.Field> fields = new ArrayList<>();
        fields.add(new DiagSnapshot.Field("mode", () -> "local"));
        fields.add(new DiagSnapshot.Field("service", () -> "running"));

        List<String> lines = DiagSnapshot.lines(fields);
        check("fields keep their declared order", Arrays.asList("mode=local", "service=running"),
                lines);

        List<DiagSnapshot.Field> broken = new ArrayList<>(fields);
        broken.add(1, new DiagSnapshot.Field("wifi_prefs", () -> {
            throw new SecurityException("credential storage is locked");
        }));
        List<String> rendered = DiagSnapshot.lines(broken);
        check("a throwing field does not remove the fields around it",
                Arrays.asList("mode=local", "wifi_prefs=unavailable", "service=running"),
                rendered);
        checkTrue("the pre-unlock Wi-Fi field is present, not missing",
                rendered.contains("wifi_prefs=unavailable"));
        check("a throwing field does not take the snapshot down", 3, rendered.size());

        List<DiagSnapshot.Field> nulls = new ArrayList<>();
        nulls.add(new DiagSnapshot.Field("null_value", () -> null));
        nulls.add(new DiagSnapshot.Field("null_supplier", null));
        check("a null value renders an empty value", "null_value=",
                DiagSnapshot.lines(nulls).get(0));
        check("a null supplier renders unavailable", "null_supplier=unavailable",
                DiagSnapshot.lines(nulls).get(1));

        check("a value containing a newline stays on one line", "mode=local next",
                DiagSnapshot.lines(new ArrayList<>(Arrays.asList(
                        new DiagSnapshot.Field("mode", () -> "local\nnext")))).get(0));
        check("render writes one trailing newline per field", "mode=local\n",
                DiagSnapshot.render(new ArrayList<>(Arrays.asList(
                        new DiagSnapshot.Field("mode", () -> "local")))));
        check("an empty snapshot renders an empty string", "",
                DiagSnapshot.render(new ArrayList<DiagSnapshot.Field>()));

        report("DiagSnapshotTest");
    }
}

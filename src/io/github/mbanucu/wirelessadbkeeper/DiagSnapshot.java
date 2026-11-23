package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A status snapshot assembled field by field, without Android.
 *
 * <p>Each field is read in isolation. Before the first unlock, credential-
 * protected Wi-Fi prefs throw; that must render as {@code unavailable} for the
 * field that needs them, not remove the field or take down the rest of the
 * snapshot. The page exists to describe exactly that state.
 */
public final class DiagSnapshot {

    public static final String UNAVAILABLE = "unavailable";

    private DiagSnapshot() {
    }

    /** One {@code name=value} line, with its own supplier. */
    public static final class Field {
        private final String name;
        private final Supplier<String> value;

        public Field(String name, Supplier<String> value) {
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public Supplier<String> value() {
            return value;
        }
    }

    /** Renders {@code name=value} lines; a throwing supplier renders unavailable. */
    public static List<String> lines(List<Field> fields) {
        List<String> out = new ArrayList<>();
        if (fields == null) return out;
        for (Field field : fields) {
            if (field == null) continue;
            out.add(field.name() + "=" + renderValue(field.value()));
        }
        return out;
    }

    /** As {@link #lines}, joined with one trailing newline per field. */
    public static String render(List<Field> fields) {
        StringBuilder out = new StringBuilder();
        for (String line : lines(fields)) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String renderValue(Supplier<String> value) {
        if (value == null) return UNAVAILABLE;
        try {
            return oneLine(value.get());
        } catch (Throwable unavailable) {
            return UNAVAILABLE;
        }
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}

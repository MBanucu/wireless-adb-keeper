package io.github.mbanucu.wirelessadbkeeper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One finished Termux {@code RUN_COMMAND}, reduced to what the app can show.
 *
 * <p>Termux sends stdout, stderr, the exit code and its own error fields back
 * through the {@code RUN_COMMAND_PENDING_INTENT} caller (Termux &ge; 0.109).
 * The scripts answer with one final line, {@code RESULT key=value ...}, so the
 * app no longer has to infer success from a port probe. This class is pure
 * Java: the parser is checked by {@code tools/ResultProbe.java} without a
 * device, and the service that receives the bundle is only a thin adapter.
 */
public final class TermuxResult {
    /** Termux's "no internal error" code, Activity.RESULT_OK. */
    static final int ERR_OK = -1;

    public final String script;
    public final String stdout;
    public final String stderr;
    public final String errmsg;
    /** Null when Termux did not report an exit code (it failed before the shell). */
    public final Integer exitCode;
    public final int errCode;
    /** The text after {@code RESULT}, or null when stdout had no such line. */
    public final String line;
    public final Map<String, String> values;
    public final long at;

    private TermuxResult(String script, String stdout, String stderr, String errmsg,
                         Integer exitCode, int errCode, String line, long at) {
        this.script = script == null ? "" : script;
        this.stdout = stdout;
        this.stderr = stderr;
        this.errmsg = errmsg;
        this.exitCode = exitCode;
        this.errCode = errCode;
        this.line = line;
        this.values = parseValues(line);
        this.at = at;
    }

    public static TermuxResult parse(String script, String stdout, String stderr,
                                     Integer exitCode, int errCode, String errmsg, long at) {
        String out = stdout == null ? "" : stdout;
        return new TermuxResult(script, out, stderr == null ? "" : stderr,
                errmsg == null ? "" : errmsg, exitCode, errCode,
                lastResultLine(out), at);
    }

    /** True when Termux itself and the script both reported success. */
    public boolean ok() {
        return line != null && !values.containsKey("error")
                && exitCode != null && exitCode == 0 && errCode == ERR_OK;
    }

    /** The script said "pinned", not merely "did not fail". */
    public boolean reportsPinned() {
        return ok() && values.containsKey("pinned");
    }

    public boolean isPinScript() {
        return "pin-adb-port.sh".equals(basename(script));
    }

    public boolean isWifiScript() {
        return "wifi-on.sh".equals(basename(script));
    }

    /** The final RESULT payload, or an explanation when there was none. */
    public String summary() {
        if (line != null) {
            return line.isEmpty() ? "RESULT" : line;
        }
        String err = firstLine(errmsg);
        if (!err.isEmpty()) return "no RESULT line: " + cap(err);
        return "no RESULT line";
    }

    /** One line for the Status page: payload, exit code and the stderr line. */
    public String uiLine() {
        StringBuilder out = new StringBuilder(summary());
        out.append(" (exit ").append(exitText()).append(')');
        String err = firstLine(stderr);
        if (!err.isEmpty()) out.append("; ").append(cap(err));
        return out.toString();
    }

    /** One line for the diagnostic journal, with both streams and Termux's error. */
    public String journal() {
        StringBuilder out = new StringBuilder();
        out.append("script=").append(basename(script))
                .append(" result=").append(line == null ? "none" : line)
                .append(" exit=").append(exitText())
                .append(" err=").append(errCode);
        String err = firstLine(stderr);
        if (!err.isEmpty()) out.append(" stderr=").append(cap(err));
        String message = firstLine(errmsg);
        if (!message.isEmpty()) out.append(" errmsg=").append(cap(message));
        return out.toString();
    }

    /** The last {@code RESULT ...} line in stdout; lines before it are ignored. */
    static String lastResultLine(String stdout) {
        String found = null;
        String text = stdout.replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("RESULT")) continue;
            if (line.length() > 6 && !Character.isWhitespace(line.charAt(6))) continue;
            found = line.length() > 6 ? line.substring(7).trim() : "";
        }
        return found;
    }

    private static Map<String, String> parseValues(String line) {
        if (line == null || line.isEmpty()) return Collections.emptyMap();
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : line.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            values.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return Collections.unmodifiableMap(values);
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String firstLine(String text) {
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private String exitText() {
        return exitCode == null ? "?" : String.valueOf(exitCode);
    }

    private static String cap(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}

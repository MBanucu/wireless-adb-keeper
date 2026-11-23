package io.github.mbanucu.wirelessadbkeeper;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Receives what Termux sends back for a {@code RUN_COMMAND} the app started.
 *
 * <p>Termux &ge; 0.109 sends the result to the {@code PendingIntent} in the
 * {@code com.termux.RUN_COMMAND_PENDING_INTENT} extra. The base intent names
 * this service and carries the script path; Termux adds a {@code result}
 * {@link Bundle} with {@code stdout}, {@code stderr}, {@code exitCode},
 * {@code err} and {@code errmsg}. The keys are copied from TermuxConstants
 * ({@code TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE*}); Termux's own wiki is
 * the reference, and {@code tools/ResultProbe.java} pins the parser's half.
 *
 * <p>This is deliberately a service, not a receiver: it is the shape Termux's
 * RUN_COMMAND example uses, and the PendingIntent is created by this app, so
 * the service does not need to be exported.
 */
public final class TermuxResultService extends Service {
    static final String EXTRA_SCRIPT = "io.github.mbanucu.wirelessadbkeeper.SCRIPT";

    // TermuxConstants.TERMUX_SERVICE / RUN_COMMAND_SERVICE.
    static final String RESULT_BUNDLE = "result";
    static final String RESULT_STDOUT = "stdout";
    static final String RESULT_STDERR = "stderr";
    static final String RESULT_EXIT_CODE = "exitCode";
    static final String RESULT_ERR = "err";
    static final String RESULT_ERRMSG = "errmsg";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            record(intent);
        } catch (Throwable t) {
            // A result delivery must never take the process down.
            Log.w(Ensurer.TAG, "Termux result could not be read: " + t);
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void record(Intent intent) {
        if (intent == null) return;
        String script = intent.getStringExtra(EXTRA_SCRIPT);
        Bundle bundle = intent.getBundleExtra(RESULT_BUNDLE);
        if (bundle == null) {
            Diag.warn(this, "script.no_result",
                    "script=" + basename(script) + " Termux sent no result bundle");
            return;
        }

        Integer exitCode = bundle.containsKey(RESULT_EXIT_CODE)
                ? bundle.getInt(RESULT_EXIT_CODE) : null;
        TermuxResult result = TermuxResult.parse(
                script,
                bundle.getString(RESULT_STDOUT, ""),
                bundle.getString(RESULT_STDERR, ""),
                exitCode,
                bundle.getInt(RESULT_ERR, TermuxResult.ERR_OK),
                bundle.getString(RESULT_ERRMSG, ""),
                SystemClock.elapsedRealtime());

        String code = result.isPinScript() ? "pin.result" : "script.result";
        if (result.isPinScript()) {
            // PinnedPort owns the pin reason codes: one event, with the specific
            // cause (allow-external-apps, refused, non-zero, ...).
            PinnedPort.recordResult(this, result);
            return;
        }
        if (result.ok()) {
            Diag.info(this, code, result.journal());
        } else {
            Diag.warn(this, code, result.journal());
        }
        if (result.isWifiScript()) {
            WifiRadio.recordResult(result);
        }
    }

    private static String basename(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package io.github.mbanucu.wirelessadbkeeper;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

public class EnsureJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Ensurer.ensure(EnsureJobService.this);
                // Fallback for a phone whose monitor service was killed. A job
                // runs in the background, so asking Termux may be refused (the
                // monitor service is the place that can); PinnedPort logs it.
                PinnedPort.check(EnsureJobService.this);
                registerSuggestionsIfServiceStopped();
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    /**
     * The persisted job is the only part of the keeper that still runs after a
     * ROM's autostart manager stops the monitor. In auto-connect mode it
     * re-registers the suggestions itself, so a dropped suggestion is repaired
     * without the foreground service. It does nothing before the first unlock
     * (the prefs and the Keystore are unreadable) or while the monitor is alive
     * (the monitor owns the same pass and the two must not race).
     */
    private void registerSuggestionsIfServiceStopped() {
        try {
            boolean unlocked = LocalWifi.storageUnlocked(this);
            if (!BootFlow.shouldRegisterFromJob(unlocked, MonitorService.running,
                    LocalWifi.isAutoMode(this))) {
                return;
            }
            SuggestionRegistry.Outcome outcome = WifiSuggestion.addAll(this);
            Diag.info(this, "wifi.suggest.job", "monitor stopped; re-registered the saved"
                    + " suggestions: " + outcome.detail() + " (status " + outcome.status() + ")");
        } catch (Exception e) {
            // A job that throws is a job the system may stop scheduling.
            Log.w(Ensurer.TAG, "job: suggestion fallback failed: " + e);
            Diag.warn(this, "wifi.suggest.job_failed",
                    "the stopped-monitor suggestion fallback failed: " + e);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

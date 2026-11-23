package io.github.mbanucu.wirelessadbkeeper;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    public static final int PERIODIC_JOB = 1001;
    public static final int ONE_SHOT_JOB = 1002;

    @Override
    public void onReceive(Context context, Intent intent) {
        // LOCKED_BOOT_COMPLETED arrives before the first unlock. The service
        // starts its pin loop there and defers anything that needs credential-
        // protected storage (the Wi-Fi prefs) until ACTION_USER_UNLOCKED.
        Log.i(Ensurer.TAG, "boot: " + (intent == null ? null : intent.getAction()));

        Intent svc = new Intent(context, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(Ensurer.TAG, "could not start MonitorService on boot", e);
        }

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        JobInfo periodic = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, EnsureJobService.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(periodic);

        JobInfo now = new JobInfo.Builder(ONE_SHOT_JOB, new ComponentName(context, EnsureJobService.class))
                .setOverrideDeadline(0)
                .setPersisted(true)
                .build();
        js.schedule(now);
    }
}

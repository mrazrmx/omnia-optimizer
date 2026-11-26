package com.omnia.optimizer;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

/**
 * Gatilho passivo via JobScheduler com recarga obrigatória (0% de uso fora da tomada).
 */
public class ChargingJobService extends JobService {
    private static final String TAG = "OmniaChargingJob";
    public static final int JOB_ID_ON_CHARGE = 1001;
    private static final long RECHECK_WHILE_PLUGGED_MS = 35 * 60 * 1000L;

    public static void scheduleChargeJob(Context context, long minLatencyMs) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            ComponentName comp = new ComponentName(context, ChargingJobService.class);
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_ON_CHARGE, comp)
                    .setRequiresCharging(true)
                    .setPersisted(true);
            if (minLatencyMs > 0) {
                builder.setMinimumLatency(minLatencyMs);
            }

            int res = js.schedule(builder.build());
            Log.i(TAG, "JobScheduler armado (latência=" + (minLatencyMs / 60000) + "m, res=" + res + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Falha ao agendar ChargingJobService", t);
        }
    }

    public static void ensureArmed(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int plugged = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;

            if (plugged == 0) {
                PowerConnectionReceiver.markUnplugged(context);
                scheduleChargeJob(context, 0);
            } else if (js.getPendingJob(JOB_ID_ON_CHARGE) == null) {
                scheduleChargeJob(context, RECHECK_WHILE_PLUGGED_MS);
            }
        } catch (Throwable t) {
            scheduleChargeJob(context, RECHECK_WHILE_PLUGGED_MS);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "ChargingJobService iniciado ao carregar.");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PowerConnectionReceiver.runCollectionSafely(getApplicationContext(), "JobScheduler", false);
                } finally {
                    scheduleChargeJob(getApplicationContext(), RECHECK_WHILE_PLUGGED_MS);
                    jobFinished(params, false);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        PowerConnectionReceiver.markUnplugged(getApplicationContext());
        scheduleChargeJob(getApplicationContext(), 0);
        return false;
    }
}

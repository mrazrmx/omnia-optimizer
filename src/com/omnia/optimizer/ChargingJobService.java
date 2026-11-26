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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Gatilho passivo via JobScheduler com recarga obrigatória (0% de uso fora da tomada).
 * Chama jobFinished() antes do reagendamento para evitar auto-cancelamento em loop via onStopJob().
 */
public class ChargingJobService extends JobService {
    private static final String TAG = "OmniaChargingJob";
    public static final int JOB_ID_ON_CHARGE = 1001;
    private static final long RECHECK_WHILE_PLUGGED_MS = 35 * 60 * 1000L;
    private static final long POST_FINISH_REARM_DELAY_MS = 2000L;

    private static boolean isPlugged(Context context) {
        try {
            Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int plugged = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
            return plugged != 0;
        } catch (Throwable t) {
            return false;
        }
    }

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

            if (!isPlugged(context)) {
                PowerConnectionReceiver.markUnplugged(context);
                if (js.getPendingJob(JOB_ID_ON_CHARGE) == null) {
                    scheduleChargeJob(context, 0);
                }
            } else if (js.getPendingJob(JOB_ID_ON_CHARGE) == null) {
                scheduleChargeJob(context, RECHECK_WHILE_PLUGGED_MS);
            }
        } catch (Throwable t) {
            scheduleChargeJob(context, RECHECK_WHILE_PLUGGED_MS);
        }
    }

    private static void rearmAfterCompletion(Context appCtx) {
        if (!isPlugged(appCtx)) {
            PowerConnectionReceiver.markUnplugged(appCtx);
            scheduleChargeJob(appCtx, 0);
        } else {
            scheduleChargeJob(appCtx, RECHECK_WHILE_PLUGGED_MS);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "ChargingJobService iniciado ao carregar.");
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PowerConnectionReceiver.runCollectionSafely(appCtx, "JobScheduler", false);
                } finally {
                    jobFinished(params, false);
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            rearmAfterCompletion(appCtx);
                        }
                    }, POST_FINISH_REARM_DELAY_MS);
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Context appCtx = getApplicationContext();
        if (!isPlugged(appCtx)) {
            PowerConnectionReceiver.markUnplugged(appCtx);
            scheduleChargeJob(appCtx, 0);
        } else {
            scheduleChargeJob(appCtx, RECHECK_WHILE_PLUGGED_MS);
        }
        return false;
    }
}

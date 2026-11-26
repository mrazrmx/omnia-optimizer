package com.omnia.optimizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.Locale;

public class PowerConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "OmniaOptimizer";
    private static final String PREFS_NAME = "OmniaPrefs";
    private static final String KEY_LAST_RUN = "last_run_timestamp";
    private static final String KEY_AWAITING_UNPLUG = "awaiting_unplug";
    private static final String KEY_LAST_DISCHARGE_MAH = "last_discharge_mah";
    private static final String KEY_LAST_START_CLOCK = "last_start_clock";
    private static final String CHANNEL_ID = "omnia_battery_cycle";
    private static final long DEBOUNCE_MS = 2 * 60 * 1000L;
    private static final Object COLLECTION_LOCK = new Object();

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ChargingJobService.ensureArmed(context);
            return;
        }

        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            markUnplugged(context);
            ChargingJobService.scheduleChargeJob(context, 0);
            return;
        }

        final boolean isExplicit = "com.omnia.optimizer.COLLECT_NOW".equals(action);
        final boolean isConnected = Intent.ACTION_POWER_CONNECTED.equals(action);
        if (!isConnected && !isExplicit) return;

        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runCollectionSafely(context, isExplicit ? "Explicit" : "PowerConnected", isExplicit);
                } finally {
                    pendingResult.finish();
                }
            }
        }).start();
    }

    public static void markUnplugged(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_AWAITING_UNPLUG, false).apply();
    }

    public static void runCollectionSafely(Context context, String source, boolean forceExplicit) {
        synchronized (COLLECTION_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastRun = prefs.getLong(KEY_LAST_RUN, 0);
            boolean awaitingUnplug = prefs.getBoolean(KEY_AWAITING_UNPLUG, false);
            String prevClock = prefs.getString(KEY_LAST_START_CLOCK, "");
            float prevMah = prefs.getFloat(KEY_LAST_DISCHARGE_MAH, -1f);
            long now = System.currentTimeMillis();

            DumpHelper.FastSignature curSig = DumpHelper.readFastSignature();
            if (!forceExplicit) {
                if (now - lastRun < DEBOUNCE_MS) {
                    return;
                }
                boolean hasClock = curSig.startClockTime != null && !curSig.startClockTime.isEmpty();
                boolean sameClock = hasClock && curSig.startClockTime.equals(prevClock);
                boolean meaningfulExtraDischarge = curSig.totalDischargeMah > 0
                        && (prevMah < 0 || Math.abs(curSig.totalDischargeMah - prevMah) >= 80.0);

                if (sameClock && !meaningfulExtraDischarge) {
                    return;
                }
                if (awaitingUnplug && !hasClock && !meaningfulExtraDischarge) {
                    return;
                }
            }

            prefs.edit()
                    .putLong(KEY_LAST_RUN, now)
                    .putBoolean(KEY_AWAITING_UNPLUG, true)
                    .putString(KEY_LAST_START_CLOCK, curSig.startClockTime != null ? curSig.startClockTime : "")
                    .putFloat(KEY_LAST_DISCHARGE_MAH, (float) curSig.totalDischargeMah)
                    .apply();

            try {
                DumpHelper.performCollection(context, null);
                showCycleNotification(context);
            } catch (Throwable t) {
                Log.e(TAG, "Erro na coleta automática (" + source + ")", t);
            }
        }
    }

    private static void showCycleNotification(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String alertChannelId = "omnia_wakelock_alert";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Ciclos de Bateria", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Notificações de ciclo de bateria finalizado");
                nm.createNotificationChannel(channel);

                NotificationChannel alertCh = new NotificationChannel(alertChannelId, "Sentinela Anti-Wakelock", NotificationManager.IMPORTANCE_HIGH);
                alertCh.setDescription("Alertas quando um app ou wakelock drena bateria em repouso");
                nm.createNotificationChannel(alertCh);
            }

            List<CycleData> list = CycleData.loadAllCycles();
            String title = "🔋 Omnia Optimizer · Ciclo Salvo";
            String content = "Ciclo salvo em Documentos/OmniaOptimizer.";
            boolean isAlert = false;
            boolean isCritical = false;

            if (!list.isEmpty()) {
                CycleData c = list.get(0);
                int h = (int) (c.screenOnSeconds / 3600);
                int m = (int) ((c.screenOnSeconds % 3600) / 60);
                String baseLine = String.format(Locale.US, "Tela: %dh%02dm | Sono: %.0f%% | Taxa: %.0f mAh/h",
                        h, m, c.deepSleepPercent, c.rateOnMahH);
                isCritical = "CRITICO".equals(c.sentinelSeverity);
                isAlert = isCritical || "ATENCAO".equals(c.sentinelSeverity);
                if (isAlert && c.sentinelSummary != null && !c.sentinelSummary.isEmpty()) {
                    title = isCritical ? "🚨 Omnia Sentinela · Ladrão em 2º Plano!" : "⚠️ Omnia Sentinela · Atenção no Repouso";
                    content = c.sentinelSummary + "\n" + baseLine;
                } else {
                    content = baseLine + " · Sentinela Limpo ✅";
                }
            }

            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 102, launchIntent, piFlags);

            Notification.Builder nb;
            String useChannel = isAlert ? alertChannelId : CHANNEL_ID;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new Notification.Builder(context, useChannel);
            } else {
                nb = new Notification.Builder(context);
            }

            nb.setContentTitle(title)
              .setContentText(content)
              .setStyle(new Notification.BigTextStyle().bigText(content))
              .setContentIntent(pi)
              .setSmallIcon(isAlert ? android.R.drawable.stat_sys_warning : android.R.drawable.ic_lock_idle_charging)
              .setAutoCancel(true);

            nm.notify(isAlert ? 102 : 101, nb.build());
        } catch (Throwable t) {
            Log.e(TAG, "Erro ao exibir notificação", t);
        }
    }
}

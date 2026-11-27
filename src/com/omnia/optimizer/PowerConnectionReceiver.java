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
    private static final String KEY_LAST_EMERGENCY_CLOCK = "last_emergency_clock";
    private static final String KEY_LAST_EMERGENCY_SHUTDOWN_CLOCK = "last_emergency_shutdown_clock";
    private static final String KEY_LAST_EMERGENCY_RUN_MS = "last_emergency_run_ms";
    private static final String ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF";
    private static final String CHANNEL_ID = "omnia_battery_cycle";
    private static final long DEBOUNCE_MS = 2 * 60 * 1000L;
    private static final Object COLLECTION_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService EMERGENCY_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ChargingJobService.ensureArmed(context);
            OmniaWidgetProvider.requestWidgetUpdate(context);
            return;
        }

        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            markUnplugged(context);
            ChargingJobService.scheduleChargeJob(context, 0);
            return;
        }

        if (Intent.ACTION_BATTERY_LOW.equals(action)
                || Intent.ACTION_SHUTDOWN.equals(action)
                || ACTION_QUICKBOOT_POWEROFF.equals(action)) {
            final boolean isShutdown = Intent.ACTION_SHUTDOWN.equals(action)
                    || ACTION_QUICKBOOT_POWEROFF.equals(action);
            OmniaApplication.BatterySnapshot snap = OmniaApplication.readStickyBattery(
                    context, isShutdown ? null : intent);
            if (isShutdown && snap.plugged && snap.pct > 15) return;
            if (!isShutdown && (snap.plugged || snap.pct <= 0 || snap.pct > 6)) return;
            final String reason = (isShutdown ? "Shutdown_" : "BatteryLow_")
                    + (snap.pct >= 0 ? snap.pct + "pct" : "SO");
            final PendingResult pendingResult = goAsync();
            EMERGENCY_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        runEmergencyCollectionSafely(context, reason, isShutdown);
                    } finally {
                        pendingResult.finish();
                    }
                }
            });
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

    public static void triggerEmergencyCollection(final Context context, final String source, final boolean fastShutdown) {
        final Context appCtx = context.getApplicationContext();
        EMERGENCY_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                runEmergencyCollectionSafely(appCtx, source, fastShutdown);
            }
        });
    }

    public static void runEmergencyCollectionSafely(Context context, String source, boolean fastShutdown) {
        synchronized (COLLECTION_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long lastEmgRun = prefs.getLong(KEY_LAST_EMERGENCY_RUN_MS, 0);
            if (!fastShutdown && now - lastEmgRun < 45_000L) {
                return;
            }

            DumpHelper.FastSignature curSig = DumpHelper.readFastSignature();
            boolean hasClock = curSig.startClockTime != null && !curSig.startClockTime.isEmpty();
            if (hasClock) {
                String clockKey = fastShutdown ? KEY_LAST_EMERGENCY_SHUTDOWN_CLOCK : KEY_LAST_EMERGENCY_CLOCK;
                if (curSig.startClockTime.equals(prefs.getString(clockKey, ""))) {
                    return;
                }
            } else if (now - lastEmgRun < 120_000L) {
                return;
            }

            if (curSig.totalDischargeMah < 20.0 && curSig.screenOnSeconds < 30.0 && curSig.screenOffSeconds < 30.0) {
                return;
            }

            SharedPreferences.Editor ed = prefs.edit()
                    .putLong(KEY_LAST_EMERGENCY_RUN_MS, now)
                    .putFloat(KEY_LAST_DISCHARGE_MAH, (float) curSig.totalDischargeMah);
            if (hasClock) {
                ed.putString(KEY_LAST_EMERGENCY_CLOCK, curSig.startClockTime);
                if (fastShutdown) {
                    ed.putString(KEY_LAST_EMERGENCY_SHUTDOWN_CLOCK, curSig.startClockTime);
                }
                ed.putString(KEY_LAST_START_CLOCK, curSig.startClockTime);
            }
            ed.apply();

            Log.i(TAG, "Salvamento pré-desligamento acionado (" + source + ", fast=" + fastShutdown + ")");
            String note = "⚡ Salvamento automático pré-desligamento (" + source + ")";
            try {
                DumpHelper.performCollection(context, note, fastShutdown);
                if (!fastShutdown) {
                    showCycleNotification(context, "⚡ Omnia · Ciclo Salvo Antes de Desligar (" + source + ")");
                }
                OmniaWidgetProvider.requestWidgetUpdate(context);
            } catch (Throwable t) {
                Log.e(TAG, "Erro no salvamento pré-desligamento (" + source + ")", t);
            }
        }
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
                showCycleNotification(context, null);
                OmniaWidgetProvider.requestWidgetUpdate(context);
            } catch (Throwable t) {
                Log.e(TAG, "Erro na coleta automática (" + source + ")", t);
            }
        }
    }

    private static void showCycleNotification(Context context, String customTitle) {
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
            String title = (customTitle != null && !customTitle.isEmpty())
                    ? customTitle
                    : "🔋 Omnia Optimizer · Ciclo Salvo";
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

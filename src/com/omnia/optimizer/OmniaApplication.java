package com.omnia.optimizer;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

/**
 * Registra em tempo de processo um receiver passivo (zero polling, zero alarme, 0% de consumo)
 * para capturar desligamentos iminentes por bateria crítica e shutdown de sistema enquanto o
 * processo estiver em cache ou ativo em memória.
 */
public class OmniaApplication extends Application {
    private static final String TAG = "OmniaOptimizer";
    private static final String ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF";
    private BroadcastReceiver emergencyBatteryReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        registerPassiveEmergencyReceiver();
    }

    private void registerPassiveEmergencyReceiver() {
        if (emergencyBatteryReceiver != null) return;
        emergencyBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (action == null) return;

                if (Intent.ACTION_SHUTDOWN.equals(action) || ACTION_QUICKBOOT_POWEROFF.equals(action)) {
                    BatterySnapshot snap = readStickyBattery(context, null);
                    if (!snap.plugged || snap.pct <= 15) {
                        String reason = "Shutdown_" + (snap.pct >= 0 ? snap.pct + "pct" : "SO");
                        PowerConnectionReceiver.triggerEmergencyCollection(context, reason, true);
                    }
                    return;
                }

                if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                    BatterySnapshot snap = readStickyBattery(context, intent);
                    if (!snap.plugged && snap.pct > 0 && snap.pct <= 6) {
                        PowerConnectionReceiver.triggerEmergencyCollection(
                                context, "BatteryLow_" + snap.pct + "pct", false);
                    }
                    return;
                }

                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    if (plugged != 0
                            || status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL) {
                        return;
                    }
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int pct = (level >= 0 && scale > 0) ? (level * 100) / scale : level;
                    if (pct <= 0 || pct > 4) {
                        return;
                    }
                    PowerConnectionReceiver.triggerEmergencyCollection(
                            context, "LowBattery_" + pct + "pct", false);
                }
            }
        };

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            filter.addAction(ACTION_QUICKBOOT_POWEROFF);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(emergencyBatteryReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(emergencyBatteryReceiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Falha ao registrar receiver de bateria crítica", t);
        }
    }

    static final class BatterySnapshot {
        int pct = -1;
        boolean plugged = false;
    }

    static BatterySnapshot readStickyBattery(Context context, Intent directIntent) {
        BatterySnapshot snap = new BatterySnapshot();
        try {
            Intent bat = directIntent;
            if (bat == null || !bat.hasExtra(BatteryManager.EXTRA_LEVEL)) {
                bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            if (bat != null) {
                int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                snap.plugged = plugged != 0
                        || status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                snap.pct = (level >= 0 && scale > 0) ? (level * 100) / scale : level;
            }
        } catch (Throwable ignored) {}
        return snap;
    }
}

package com.omnia.optimizer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Widget nativo Cyber-HUD com 0% de consumo em segundo plano:
 * - updatePeriodMillis="0" (nenhum alarme ou acordada em standby).
 * - Atualiza exclusivamente ao tocar em ↻ / 🕵️ Caçar, ao abrir o app ou após coleta no carregador.
 */
public class OmniaWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "OmniaWidget";
    public static final String ACTION_WIDGET_REFRESH = "com.omnia.optimizer.WIDGET_REFRESH";
    public static final String ACTION_WIDGET_HUNT = "com.omnia.optimizer.WIDGET_HUNT";

    private static final String PREFS_NAME = "OmniaWidgetPrefs";
    private static final String KEY_LIVE_SEVERITY = "live_sentinel_severity";
    private static final String KEY_LIVE_HEADLINE = "live_sentinel_headline";
    private static final String KEY_LIVE_TIME = "live_sentinel_time";
    private static final long LIVE_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    private static final int RC_OPEN_HUD = 301;
    private static final int RC_OPEN_PERF = 302;
    private static final int RC_REFRESH = 303;
    private static final int RC_HUNT = 304;

    private static final ExecutorService WIDGET_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        runAsyncInReceiver(context.getApplicationContext(), appWidgetManager, appWidgetIds, true, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean isRefresh = ACTION_WIDGET_REFRESH.equals(action);
        boolean isHunt = ACTION_WIDGET_HUNT.equals(action);
        if (!isRefresh && !isHunt) {
            return;
        }

        final Context appCtx = context.getApplicationContext();
        final AppWidgetManager awm = AppWidgetManager.getInstance(appCtx);
        final int[] ids = awm.getAppWidgetIds(new ComponentName(appCtx, OmniaWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }

        if (isHunt) {
            showScanningIndicator(appCtx, awm, ids);
        }
        runAsyncInReceiver(appCtx, awm, ids, true, isHunt);
    }

    /**
     * Solicita atualização oportunista somente se houver ao menos 1 widget fixado na tela inicial.
     */
    public static void requestWidgetUpdate(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        final AppWidgetManager awm = AppWidgetManager.getInstance(appCtx);
        final int[] ids = awm.getAppWidgetIds(new ComponentName(appCtx, OmniaWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        WIDGET_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    pushWidgetViews(appCtx, awm, ids, false, false);
                } catch (Throwable t) {
                    Log.e(TAG, "Falha ao atualizar widget", t);
                }
            }
        });
    }

    public static void saveLiveHuntReport(Context context, WakelockSentinel.SentinelReport rep) {
        if (context == null || rep == null) return;
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_LIVE_SEVERITY, rep.severity != null ? rep.severity : "OK")
                .putString(KEY_LIVE_HEADLINE, firstNonEmptyLine(rep.headline, "Varredura concluída"))
                .putLong(KEY_LIVE_TIME, System.currentTimeMillis())
                .apply();
        requestWidgetUpdate(context);
    }

    private void runAsyncInReceiver(final Context appCtx, final AppWidgetManager awm, final int[] ids,
                                    final boolean quickPowerScan, final boolean fullHunt) {
        final PendingResult pr = goAsync();
        WIDGET_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    pushWidgetViews(appCtx, awm, ids, quickPowerScan, fullHunt);
                } catch (Throwable t) {
                    Log.e(TAG, "Erro no executor do widget", t);
                } finally {
                    if (pr != null) {
                        try { pr.finish(); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    private static void showScanningIndicator(Context context, AppWidgetManager awm, int[] ids) {
        try {
            RemoteViews partial = new RemoteViews(context.getPackageName(), R.layout.widget_omnia);
            partial.setTextViewText(R.id.tvWidgetSentinel, "🕵️ Varrendo power e batterystats...");
            partial.setTextColor(R.id.tvWidgetSentinel, Color.parseColor("#38BDF8"));
            awm.partiallyUpdateAppWidget(ids, partial);
        } catch (Throwable ignored) {
        }
    }

    private static void pushWidgetViews(Context context, AppWidgetManager awm, int[] ids,
                                        boolean quickPowerScan, boolean fullHunt) {
        WakelockSentinel.SentinelReport liveHunt = null;
        if (fullHunt) {
            liveHunt = WakelockSentinel.scanLiveNow(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LIVE_SEVERITY, liveHunt.severity != null ? liveHunt.severity : "OK")
                    .putString(KEY_LIVE_HEADLINE, firstNonEmptyLine(liveHunt.headline, "Varredura concluída"))
                    .putLong(KEY_LIVE_TIME, System.currentTimeMillis())
                    .apply();
        }

        WakelockSentinel.SentinelReport quickPower = null;
        if (!fullHunt && quickPowerScan) {
            quickPower = WakelockSentinel.scanPowerQuick(context);
        }

        AnalyticsEngine.AnalysisResult auditRes = AnalyticsEngine.analyze(context);
        DumpHelper.FastSignature liveSig = DumpHelper.readFastSignature();

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_omnia);

        String clockStr = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        rv.setTextViewText(R.id.tvWidgetHeader, "OMNIA HUD · " + clockStr + " · 0% 2ºP");

        applyPill(rv, R.id.tvWidgetPillLeve,
                auditRes.lowHeatActive ? "LEVE ON" : "LEVE OFF",
                auditRes.lowHeatActive ? R.drawable.bg_pill_blue : R.drawable.bg_pill_red,
                auditRes.lowHeatActive ? "#93C5FD" : "#FDA4AF");

        String dimText = auditRes.extraDimActive
                ? ("DIM " + (auditRes.extraDimLevel >= 0 ? auditRes.extraDimLevel + "%" : "ON"))
                : "DIM OFF";
        applyPill(rv, R.id.tvWidgetPillDim, dimText,
                auditRes.extraDimActive ? R.drawable.bg_pill_blue : R.drawable.bg_pill_dark,
                auditRes.extraDimActive ? "#93C5FD" : "#94A3B8");

        applyPill(rv, R.id.tvWidgetPillVulkan,
                auditRes.vulkanActive ? "VULKAN" : "NO-VK!",
                auditRes.vulkanActive ? R.drawable.bg_pill_green : R.drawable.bg_pill_red,
                auditRes.vulkanActive ? "#6EE7B7" : "#FDA4AF");

        bindBatteryBlock(context, rv);
        bindLiveCycleBlock(rv, liveSig);
        CycleData latestCycle = bindCleanCycleBlock(rv);
        bindAuditBlock(context, rv, auditRes);
        bindSentinelBlock(context, rv, latestCycle, quickPower, liveHunt);

        rv.setOnClickPendingIntent(R.id.widgetRoot, createOpenTabIntent(context, MainActivity.TAB_HUD, RC_OPEN_HUD));
        rv.setOnClickPendingIntent(R.id.btnWidgetPerf, createOpenTabIntent(context, MainActivity.TAB_PERF, RC_OPEN_PERF));
        rv.setOnClickPendingIntent(R.id.btnWidgetRefresh, createWidgetActionIntent(context, ACTION_WIDGET_REFRESH, RC_REFRESH));
        rv.setOnClickPendingIntent(R.id.btnWidgetHunt, createWidgetActionIntent(context, ACTION_WIDGET_HUNT, RC_HUNT));

        awm.updateAppWidget(ids, rv);
    }

    private static void bindBatteryBlock(Context context, RemoteViews rv) {
        Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1;
        double tempC = -1;
        int voltMv = -1;
        int plugged = 0;
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && scale > 0) pct = (lvl * 100) / scale;
            int rawTemp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (rawTemp > 0) tempC = rawTemp / 10.0;
            voltMv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        }

        int chargeCounterMah = -1;
        double ma = Double.NaN;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (cc > 0) {
                    chargeCounterMah = (int) Math.round(cc > 20000L ? cc / 1000.0 : cc);
                }
                long rawCur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (rawCur != Long.MIN_VALUE && rawCur != 0L) {
                    ma = PerfMonitor.normalizeCurrentMa(rawCur);
                }
            }
        } catch (Throwable ignored) {
        }

        String srcTag = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Sem fio" : "Bat.";

        rv.setTextViewText(R.id.tvWidgetBatPct, pct >= 0 ? (pct + "%") : "--%");
        rv.setTextViewText(R.id.tvWidgetBatTitle,
                (tempC > 0 ? String.format(Locale.US, "%.1f°C", tempC) : "--°C") + " · " + srcTag);

        StringBuilder powerSb = new StringBuilder();
        if (!Double.isNaN(ma)) {
            if (voltMv > 0) {
                double watts = (voltMv / 1000.0) * (ma / 1000.0);
                powerSb.append(String.format(Locale.US, "%+.0f mA (%.2fW)", ma, Math.abs(watts)));
            } else {
                powerSb.append(String.format(Locale.US, "%+.0f mA", ma));
            }
        } else if (voltMv > 0) {
            powerSb.append(String.format(Locale.US, "%.2f V", voltMv / 1000.0));
        } else {
            powerSb.append("Corrente --");
        }
        rv.setTextViewText(R.id.tvWidgetBatPower, powerSb.toString());

        StringBuilder sub = new StringBuilder();
        if (chargeCounterMah > 0) {
            sub.append("Carga real: ").append(chargeCounterMah).append(" mAh");
        } else {
            sub.append("Carga real: --");
        }
        if (voltMv > 0) {
            sub.append(String.format(Locale.US, " · %.2fV", voltMv / 1000.0));
        }
        rv.setTextViewText(R.id.tvWidgetBatSub, sub.toString());
    }

    private static void bindLiveCycleBlock(RemoteViews rv, DumpHelper.FastSignature sig) {
        if (sig == null || (sig.totalDischargeMah <= 0 && sig.screenOnSeconds <= 0 && sig.timeOnBatterySeconds <= 0)) {
            rv.setTextViewText(R.id.tvWidgetLiveTitle, "📈 CICLO ATUAL · AO VIVO");
            rv.setTextViewText(R.id.tvWidgetLiveBody, "Aguardando permissão DUMP\nToque ↻ para tentar novamente");
            return;
        }

        double onMah = sig.screenOnDischargeMah > 0
                ? sig.screenOnDischargeMah
                : Math.max(0.0, sig.totalDischargeMah - sig.screenOffDischargeMah);
        double offMah = sig.screenOffDischargeMah;
        double rateOn = sig.screenOnSeconds >= 300 ? (onMah / (sig.screenOnSeconds / 3600.0)) : 0;
        double rateOff = sig.screenOffSeconds >= 300 ? (offMah / (sig.screenOffSeconds / 3600.0)) : 0;

        String clockBadge = "";
        if (sig.startClockTime != null && sig.startClockTime.length() >= 16) {
            clockBadge = " · " + sig.startClockTime.substring(11, 16).replace('-', ':');
        }
        rv.setTextViewText(R.id.tvWidgetLiveTitle, "📈 CICLO ATUAL" + clockBadge);

        StringBuilder body = new StringBuilder();
        body.append("Tela: ").append(AnalyticsEngine.formatDuration(sig.screenOnSeconds));
        if (rateOn > 0) {
            body.append(String.format(Locale.US, " (%.0f mAh/h)", rateOn));
        }
        body.append("\nApag: ").append(AnalyticsEngine.formatDuration(sig.screenOffSeconds));
        if (rateOff > 0) {
            body.append(String.format(Locale.US, " (%.0f mAh/h)", rateOff));
        } else if (sig.deepSleepPercent > 0) {
            body.append(String.format(Locale.US, " · Sono %.0f%%", sig.deepSleepPercent));
        }
        body.append(String.format(Locale.US, "\nGasto: %.0f mAh", sig.totalDischargeMah));
        if (sig.deepSleepPercent > 0) {
            body.append(String.format(Locale.US, " · Sono %.0f%%", sig.deepSleepPercent));
        }
        rv.setTextViewText(R.id.tvWidgetLiveBody, body.toString());
    }

    private static CycleData bindCleanCycleBlock(RemoteViews rv) {
        List<CycleData> cycles = CycleData.loadAllCycles();
        if (cycles.isEmpty()) {
            rv.setTextViewText(R.id.tvWidgetCycleTitle, "📊 ÚLT. CICLO");
            rv.setTextColor(R.id.tvWidgetCycleTitle, Color.parseColor("#94A3B8"));
            rv.setTextViewText(R.id.tvWidgetCycleSub, "Sem ciclos salvos\nColeta automática ao carregar");
            return null;
        }

        CycleData target = cycles.get(0);
        for (CycleData c : cycles) {
            if (!c.isMixedWindow()) {
                target = c;
                break;
            }
        }

        boolean mixed = target.isMixedWindow();
        String dateTag = new SimpleDateFormat("dd/MM", Locale.US).format(new Date(target.timestamp));
        rv.setTextViewText(R.id.tvWidgetCycleTitle, (mixed ? "⚠️ MISTO · " : "✅ ÚLT. LIMPO · ") + dateTag);
        rv.setTextColor(R.id.tvWidgetCycleTitle, Color.parseColor(mixed ? "#FBBF24" : "#6EE7B7"));

        String line1 = String.format(Locale.US, "Tela %.0f | Apag %.0f mAh/h", target.rateOnMahH, target.rateOffMahH);
        String line2 = String.format(Locale.US, "Sono %.1f%% · %s tela",
                target.deepSleepPercent, AnalyticsEngine.formatDuration(target.screenOnSeconds));
        rv.setTextViewText(R.id.tvWidgetCycleSub, line1 + "\n" + line2);
        return cycles.get(0);
    }

    private static void bindAuditBlock(Context context, RemoteViews rv, AnalyticsEngine.AnalysisResult res) {
        rv.setTextViewText(R.id.tvWidgetAuditTitle, "⚙️ AUDITORIA · " + res.settingsScorePct + "%");
        ContentResolver cr = context.getContentResolver();
        boolean lowPower = "1".equals(AnalyticsEngine.safeGlobal(cr, "low_power"));
        String aod = AnalyticsEngine.safeSystem(cr, "aod_mode");
        String aodNoti = AnalyticsEngine.safeSystem(cr, "aod_show_for_new_noti");
        String aodLabel = "0".equals(aod) ? "AOD OFF" : ("1".equals(aodNoti) ? "AOD Notif" : "AOD ON");

        String line1 = "Job: " + (res.autoJobArmed ? "Armado" : "Pendente");
        String line2 = (lowPower ? "Econ: ON" : "Econ: OFF") + " · " + aodLabel;
        rv.setTextViewText(R.id.tvWidgetAuditSub, line1 + "\n" + line2);
    }

    private static void bindSentinelBlock(Context context, RemoteViews rv, CycleData latestCycle,
                                          WakelockSentinel.SentinelReport quickPower,
                                          WakelockSentinel.SentinelReport liveHunt) {
        String sev = "OK";
        String text = "🛡️ Sentinela: Toque Caçar para auditar agora";

        if (liveHunt != null) {
            sev = liveHunt.severity != null ? liveHunt.severity : "OK";
            text = "🛡️ " + firstNonEmptyLine(liveHunt.headline, "Varredura concluída");
        } else if (quickPower != null && !"OK".equals(quickPower.severity)) {
            sev = quickPower.severity;
            text = "🔒 " + firstNonEmptyLine(quickPower.headline, "Lock ativo detectado");
        } else {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long liveTs = prefs.getLong(KEY_LIVE_TIME, 0L);
            long cycleTs = latestCycle != null ? latestCycle.timestamp : 0L;
            long now = System.currentTimeMillis();
            if (liveTs > 0 && liveTs >= cycleTs && (now - liveTs) < LIVE_CACHE_TTL_MS) {
                sev = prefs.getString(KEY_LIVE_SEVERITY, "OK");
                String h = prefs.getString(KEY_LIVE_HEADLINE, "Limpo agora");
                String tStr = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(liveTs));
                text = "🛡️ " + tStr + " · " + h;
            } else if (latestCycle != null && latestCycle.sentinelSummary != null && !latestCycle.sentinelSummary.isEmpty()) {
                sev = latestCycle.sentinelSeverity != null ? latestCycle.sentinelSeverity : "OK";
                text = "🛡️ Ciclo: " + firstNonEmptyLine(latestCycle.sentinelSummary, "Sem alertas");
            }
        }

        int color = "CRITICO".equals(sev) ? Color.parseColor("#FDA4AF")
                : ("ATENCAO".equals(sev) ? Color.parseColor("#FBBF24") : Color.parseColor("#6EE7B7"));
        rv.setTextViewText(R.id.tvWidgetSentinel, text);
        rv.setTextColor(R.id.tvWidgetSentinel, color);
    }

    private static void applyPill(RemoteViews rv, int viewId, String text, int bgResId, String hexColor) {
        rv.setTextViewText(viewId, text);
        rv.setInt(viewId, "setBackgroundResource", bgResId);
        rv.setTextColor(viewId, Color.parseColor(hexColor));
    }

    private static PendingIntent createOpenTabIntent(Context context, int tabIndex, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_TAB, tabIndex);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    private static PendingIntent createWidgetActionIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, OmniaWidgetProvider.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static String firstNonEmptyLine(String raw, String fallback) {
        if (raw == null) return fallback;
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return fallback;
    }
}

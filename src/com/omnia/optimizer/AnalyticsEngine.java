package com.omnia.optimizer;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AnalyticsEngine {

    public static class AnalysisResult {
        public String hardwareSummary;
        public String hardwareTileLeft;
        public String hardwareTileRight;
        public String currentSummary;
        public String screenTimeComparison;
        public String rateComparison;
        public String causalDiagnosis;
        public String auditReport;
        public String historySummary;
        public String latestUserNote;
        public String permissionStatusText;

        public float[] radarCurrent = new float[]{0.8f, 0.8f, 0.85f, 0.8f, 0.8f};
        public float[] radarBaseline = new float[]{0.75f, 0.70f, 0.78f, 0.82f, 0.80f};
        public boolean lowHeatActive;
        public boolean hasLowHeatSetting;
        public boolean extraDimActive;
        public boolean hasExtraDimSetting;
        public int extraDimLevel = -1;
        public boolean vulkanActive;
        public boolean autoJobArmed;
        public boolean coreDumpPermissionsOk;
        public int settingsScorePct;
        public List<CycleData> trendCycles;
        public String sentinelSeverity = "OK";
        public String sentinelSummary = "";
    }

    public static String getDeviceHeaderSubtitle() {
        String manuf = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim().toUpperCase(Locale.US) : "GALAXY";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "ANDROID";
        String oneUiRaw = getSystemProp("ro.build.version.oneui", "");
        String osLabel;
        if (!oneUiRaw.isEmpty()) {
            try {
                int v = Integer.parseInt(oneUiRaw.trim());
                int major = v / 10000;
                int minor = (v % 10000) / 100;
                osLabel = "ONE UI " + major + "." + minor;
            } catch (NumberFormatException e) {
                osLabel = "ANDROID " + Build.VERSION.RELEASE;
            }
        } else {
            osLabel = "ANDROID " + Build.VERSION.RELEASE;
        }
        String soc = getSystemProp("ro.soc.model", "");
        if (!soc.isEmpty()) {
            return manuf + " " + model + " · " + osLabel + " · " + soc.toUpperCase(Locale.US);
        }
        return manuf + " " + model + " · " + osLabel;
    }

    public static AnalysisResult analyze(Context context) {
        List<CycleData> cycles = CycleData.loadAllCycles();
        AnalysisResult res = new AnalysisResult();

        buildHardwareTelemetry(context, res);
        buildAuditAndScore(context, res);

        if (cycles.isEmpty()) {
            res.currentSummary = res.coreDumpPermissionsOk
                    ? "Nenhum ciclo registrado ainda.\nConecte o carregador após usar ou toque em ⚡ LER AGORA."
                    : "Base limpa pronta.\nAtive DUMP e BATTERY_STATS na aba ⚙️ OTIM (via ADB/aShell + Shizuku) para liberar coleta automática e Sentinela.";
            res.screenTimeComparison = "Aguardando ao menos 2 ciclos para comparação.";
            res.rateComparison = "Os recordes e médias limpas aparecerão a partir do 2º ciclo.";
            res.causalDiagnosis = "Sem ciclos registrados ainda.\nUse o aparelho normalmente e plugue o carregador — o Omnia registra e analisa sozinho.";
            res.historySummary = "Nenhum ciclo salvo ainda.";
            res.latestUserNote = "";
            res.trendCycles = cycles;
            return res;
        }

        CycleData current = cycles.get(0);
        res.latestUserNote = current.userContext != null ? current.userContext : "";
        res.trendCycles = cycles;
        res.sentinelSeverity = current.sentinelSeverity != null ? current.sentinelSeverity : "OK";
        res.sentinelSummary = current.sentinelSummary != null ? current.sentinelSummary : "";

        StringBuilder curSb = new StringBuilder();
        curSb.append("Registro: ").append(formatDate(current.timestamp));
        curSb.append(current.isMixedWindow() ? "\n⚠️ Status: JANELA MISTA" : "\n✅ Status: CICLO LIMPO");
        if (current.isMixedWindow() && current.integrityReason != null && !current.integrityReason.isEmpty()) {
            curSb.append(" (").append(current.integrityReason).append(")");
        }
        curSb.append("\n");

        if (current.batteryStartLevel > 0 && current.dischargePp > 0) {
            curSb.append(String.format(Locale.US, "Janela: %d%% → %d%% (-%d pp)\n",
                    current.batteryStartLevel, current.batteryLevel, current.dischargePp));
        } else {
            curSb.append(String.format(Locale.US, "Nível: %d%%", current.batteryLevel));
            if (current.batteryTempC > 0) {
                curSb.append(String.format(Locale.US, " | Temp.: %.1f °C", current.batteryTempC));
            }
            curSb.append("\n");
        }

        curSb.append(String.format(Locale.US,
                "Tela Ligada: %s | Apagada: %s\n" +
                "Taxa Ligada: %.0f mAh/h | Apagada: %.0f mAh/h\n" +
                "Sono Profundo: %.1f%%",
                formatDuration(current.screenOnSeconds),
                formatDuration(current.screenOffSeconds),
                current.rateOnMahH, current.rateOffMahH,
                current.deepSleepPercent));

        if (current.screenDozeSeconds > 30 || current.screenDozeDischargeMah > 0) {
            curSb.append(String.format(Locale.US, "\nAOD (Doze): %s", formatDuration(current.screenDozeSeconds)));
            if (current.screenDozeDischargeMah > 0) {
                curSb.append(String.format(Locale.US, " · %.0f mAh", current.screenDozeDischargeMah));
                if (current.dischargeOffMah > 0) {
                    int pctOff = (int) Math.round(100.0 * current.screenDozeDischargeMah / current.dischargeOffMah);
                    curSb.append(" (").append(pctOff).append("% apag.)");
                }
            }
        }

        if (current.mobile5gSeconds > 30 || current.gnssSeconds > 30 || current.gnssMah > 1.0) {
            curSb.append("\nRádio/GPS: ");
            boolean wrote = false;
            if (current.mobile5gSeconds > 30) {
                curSb.append("5G ").append(formatDuration(current.mobile5gSeconds));
                wrote = true;
            }
            if (current.gnssSeconds > 30 || current.gnssMah > 1.0) {
                if (wrote) curSb.append(" | ");
                curSb.append("GNSS ").append(formatDuration(current.gnssSeconds));
                if (current.gnssMah > 0) curSb.append(String.format(Locale.US, " (%.0f mAh)", current.gnssMah));
            }
        }

        if (!current.isMixedWindow() && current.dischargePp >= 15 && current.screenOnSeconds > 0) {
            double projHours99 = (current.screenOnSeconds / 3600.0) * 99.0 / current.dischargePp;
            curSb.append(String.format(Locale.US, "\nProjeção linear (99 pp): %s (extrapolação)",
                    formatDuration(projHours99 * 3600.0)));
        }

        if (current.cpuMah > 0 || current.videoMah > 0 || current.mobileRadioMah > 0) {
            curSb.append(String.format(Locale.US,
                    "\nModelado (sobreposição): CPU %.0f | Vídeo %.0f | Rádio %.0f mAh",
                    current.cpuMah, current.videoMah, current.mobileRadioMah));
        }

        if (current.topAppsSummary != null && !current.topAppsSummary.isEmpty()) {
            curSb.append("\nTop Apps (UID): ").append(current.topAppsSummary);
        } else if (current.topApp != null && !current.topApp.isEmpty()) {
            curSb.append("\nApp Líder (UID): ").append(current.topApp)
                 .append(current.topAppMah > 0 ? String.format(Locale.US, " (%.0f mAh)", current.topAppMah) : "");
        }

        if (current.topWakelocksSummary != null && !current.topWakelocksSummary.isEmpty()) {
            curSb.append("\nWakelocks: ").append(current.topWakelocksSummary);
        }

        res.currentSummary = curSb.toString();

        double avgRateOn = current.rateOnMahH;
        double avgRateOff = current.rateOffMahH;
        double avgSleep = current.deepSleepPercent;
        double avgWeakS = current.weakSignalSeconds;
        double avgRadioMah = current.mobileRadioMah;

        if (cycles.size() < 2) {
            res.screenTimeComparison = "Primeiro ciclo registrado. O próximo trará comparações.";
            res.rateComparison = "Referência inicial estabelecida.";
        } else {
            CycleData prev = cycles.get(1);
            for (int i = 1; i < cycles.size(); i++) {
                if (!cycles.get(i).isMixedWindow()) {
                    prev = cycles.get(i);
                    break;
                }
            }

            double diffScreenSec = current.screenOnSeconds - prev.screenOnSeconds;
            String timeDiffStr = formatDuration(Math.abs(diffScreenSec));
            if (diffScreenSec > 300) {
                res.screenTimeComparison = "Tela ligada " + timeDiffStr + " a MAIS que no ciclo de referência.";
            } else if (diffScreenSec < -300) {
                res.screenTimeComparison = "Tela ligada " + timeDiffStr + " a MENOS que no ciclo de referência.";
            } else {
                res.screenTimeComparison = "Tempo de tela praticamente IDÊNTICO ao ciclo de referência (< 5 min).";
            }

            List<CycleData> cleanPool = new ArrayList<CycleData>();
            for (CycleData c : cycles) {
                if (!c.isMixedWindow()) cleanPool.add(c);
            }
            List<CycleData> basePool = cleanPool.isEmpty() ? cycles : cleanPool;

            int avgCount = Math.min(7, basePool.size());
            double sumOn = 0, sumRateOn = 0, sumRateOff = 0, sumSleep = 0, sumWeak = 0, sumRadio = 0;
            int validRateOn = 0, validRateOff = 0;
            double bestRateOn = Double.MAX_VALUE;
            double bestRateOff = Double.MAX_VALUE;

            for (int i = 0; i < basePool.size(); i++) {
                CycleData c = basePool.get(i);
                if (i < avgCount) {
                    sumOn += c.screenOnSeconds;
                    sumSleep += c.deepSleepPercent;
                    sumWeak += c.weakSignalSeconds;
                    sumRadio += c.mobileRadioMah;
                    if (c.rateOnMahH > 0) { sumRateOn += c.rateOnMahH; validRateOn++; }
                    if (c.rateOffMahH > 0) { sumRateOff += c.rateOffMahH; validRateOff++; }
                }
                if (c.rateOnMahH > 100 && c.rateOnMahH < bestRateOn) bestRateOn = c.rateOnMahH;
                if (c.rateOffMahH > 10 && c.rateOffMahH < bestRateOff) bestRateOff = c.rateOffMahH;
            }

            avgRateOn = validRateOn > 0 ? sumRateOn / validRateOn : current.rateOnMahH;
            avgRateOff = validRateOff > 0 ? sumRateOff / validRateOff : current.rateOffMahH;
            avgSleep = sumSleep / avgCount;
            avgWeakS = sumWeak / avgCount;
            avgRadioMah = sumRadio / avgCount;

            StringBuilder rateSb = new StringBuilder();
            if (prev.rateOnMahH > 0 && current.rateOnMahH > 0) {
                double rateDiffPct = 100.0 * (current.rateOnMahH - prev.rateOnMahH) / prev.rateOnMahH;
                if (Math.abs(rateDiffPct) < 3.0) {
                    rateSb.append(String.format(Locale.US, "Eficiência ligada estável vs referência (%+.1f%%: %.0f vs %.0f mAh/h).\n",
                            rateDiffPct, current.rateOnMahH, prev.rateOnMahH));
                } else if (rateDiffPct > 0) {
                    rateSb.append(String.format(Locale.US, "Gasto por hora ligada %.1f%% MAIOR vs referência (%.0f vs %.0f mAh/h).\n",
                            rateDiffPct, current.rateOnMahH, prev.rateOnMahH));
                } else {
                    rateSb.append(String.format(Locale.US, "Gasto por hora ligada %.1f%% MENOR vs referência (%.0f vs %.0f mAh/h).\n",
                            Math.abs(rateDiffPct), current.rateOnMahH, prev.rateOnMahH));
                }
            }

            rateSb.append(String.format(Locale.US,
                    "Média (%d ciclos %s): %s tela | %.0f mAh/h lig. | %.0f mAh/h apag. | %.1f%% repouso",
                    avgCount, cleanPool.isEmpty() ? "gerais" : "limpos",
                    formatDuration(sumOn / avgCount), avgRateOn, avgRateOff, avgSleep));

            if (bestRateOn < Double.MAX_VALUE && bestRateOff < Double.MAX_VALUE) {
                rateSb.append(String.format(Locale.US,
                        "\nRecordes limpos: %.0f mAh/h ligada | %.0f mAh/h apagada",
                        bestRateOn, bestRateOff));
            }
            res.rateComparison = rateSb.toString();
        }

        float thermalLive = getLiveThermalScore(context);
        float sysScore = Math.max(0.2f, res.settingsScorePct / 100f);

        res.radarCurrent = new float[]{
                scoreScreenRate(current.rateOnMahH),
                scoreSleep(current.deepSleepPercent, current.rateOffMahH),
                scoreRadio(current.weakSignalSeconds, current.mobileRadioMah),
                thermalLive,
                sysScore
        };
        res.radarBaseline = new float[]{
                scoreScreenRate(avgRateOn),
                scoreSleep(avgSleep, avgRateOff),
                scoreRadio(avgWeakS, avgRadioMah),
                0.85f,
                0.85f
        };

        StringBuilder causes = new StringBuilder();
        if (current.isMixedWindow()) {
            causes.append("⚖️ INTEGRIDADE DA JANELA: MISTA (")
                  .append(current.integrityReason != null && !current.integrityReason.isEmpty() ? current.integrityReason : "recarga parcial ou reinício")
                  .append("). Evite comparar diretamente com ciclos limpos.\n\n");
        }
        if (current.weakSignalSeconds > 1800 || current.mobileRadioMah > 180 || current.mobile5gSeconds > 1200) {
            int weakMin = (int) (current.weakSignalSeconds / 60);
            int m5gMin = (int) (current.mobile5gSeconds / 60);
            causes.append("📡 SINAL CELULAR: ");
            if (weakMin > 0) causes.append(weakMin).append(" min em sinal fraco");
            else causes.append("uso intenso de modem");
            if (m5gMin > 0) causes.append(" · ").append(m5gMin).append(" min em 5G NR");
            if (current.mobileRadioMah > 0) causes.append(String.format(Locale.US, " (rádio %.0f mAh)", current.mobileRadioMah));
            causes.append(". Dica: preferir LTE ou Wi-Fi Calling + Modo Avião em áreas sem cobertura poupa modem.\n\n");
        }
        if (current.lightBrightnessPercent > 15.0) {
            causes.append(String.format(Locale.US, "☀️ BRILHO ALTO: Display claro/forte em %.1f%% do tempo de tela.\n\n",
                    current.lightBrightnessPercent));
        }
        if (current.screenOnSeconds > 300 && current.highRefreshSeconds > 0) {
            double highPct = 100.0 * current.highRefreshSeconds / current.screenOnSeconds;
            if (highPct > 65.0) {
                causes.append(String.format(Locale.US, "⚡ ALTA TAXA DE ATUALIZAÇÃO: %.0f%% do uso ativo.\n\n", highPct));
            }
        }
        if (current.videoMah > 45.0 || current.cameraMah > 35.0 || current.gnssMah > 25.0 || current.batteryTempC >= 39.0) {
            causes.append("🔥 CARGA INTENSA: ");
            if (current.videoMah > 45.0) causes.append(String.format(Locale.US, "Vídeo %.0f mAh ", current.videoMah));
            if (current.cameraMah > 35.0) causes.append(String.format(Locale.US, "Câmera %.0f mAh ", current.cameraMah));
            if (current.gnssMah > 25.0) causes.append(String.format(Locale.US, "GNSS %.0f mAh ", current.gnssMah));
            if (current.batteryTempC >= 39.0) causes.append(String.format(Locale.US, "Temp. %.1f °C ", current.batteryTempC));
            causes.append("\n\n");
        }
        if (current.deepSleepPercent < 60.0 || current.rateOffMahH > 130.0 || current.screenDozeDischargeMah > 80.0 ||
                (current.topWakelocksSummary != null && !current.topWakelocksSummary.isEmpty())) {
            causes.append(String.format(Locale.US, "💤 RAIO-X DO REPOUSO: Sono profundo em %.1f%% (%.0f mAh/h apagada).",
                    current.deepSleepPercent, current.rateOffMahH));
            if (current.screenDozeDischargeMah > 0 || current.screenDozeSeconds > 60) {
                causes.append(String.format(Locale.US, "\n   • AOD (Screen doze): %s", formatDuration(current.screenDozeSeconds)));
                if (current.screenDozeDischargeMah > 0) {
                    causes.append(String.format(Locale.US, " · %.0f mAh", current.screenDozeDischargeMah));
                    if (current.dischargeOffMah > 0) {
                        causes.append(String.format(Locale.US, " (%.0f%% apag.)",
                                100.0 * current.screenDozeDischargeMah / current.dischargeOffMah));
                    }
                }
            }
            if (current.topWakelocksSummary != null && !current.topWakelocksSummary.isEmpty()) {
                causes.append("\n   • Principais locks: ").append(current.topWakelocksSummary);
            }
            causes.append("\n\n");
        }
        if (current.topAppMah > 150 && current.topApp != null && !current.topApp.isEmpty()) {
            causes.append(String.format(Locale.US, "📱 APP DESTACADO: %s liderou estimativas UID com %.0f mAh.\n\n",
                    current.topApp, current.topAppMah));
        }
        if (current.userContext != null && !current.userContext.trim().isEmpty()) {
            causes.append("📝 NOTA: ").append(current.userContext.trim());
        } else if (causes.length() == 0) {
            causes.append("✅ USO EQUILIBRADO: Sem anomalias de rádio, brilho ou quebra severa de sono profundo.");
        }
        res.causalDiagnosis = causes.toString().trim();

        StringBuilder histSb = new StringBuilder();
        int limit = Math.min(10, cycles.size());
        for (int i = 0; i < limit; i++) {
            CycleData c = cycles.get(i);
            histSb.append(String.format(Locale.US, "• %s %s | %s | %.0f/%.0f mAh/h | Sleep %.0f%%",
                    formatShortDate(c.timestamp),
                    c.isMixedWindow() ? "[MISTO]" : "[LIMPO]",
                    formatDuration(c.screenOnSeconds),
                    c.rateOnMahH, c.rateOffMahH, c.deepSleepPercent));
            if (c.batteryStartLevel > 0 && c.dischargePp > 0) {
                histSb.append(String.format(Locale.US, " | %d→%d%%", c.batteryStartLevel, c.batteryLevel));
            } else {
                histSb.append(String.format(Locale.US, " | %d%%", c.batteryLevel));
            }
            if (c.userContext != null && !c.userContext.trim().isEmpty()) {
                String shortCtx = c.userContext.trim();
                if (shortCtx.length() > 54) shortCtx = shortCtx.substring(0, 51) + "...";
                histSb.append("\n   ↳ ").append(shortCtx);
            }
            if (i < limit - 1) histSb.append("\n");
        }
        res.historySummary = histSb.toString();

        return res;
    }

    private static float scoreScreenRate(double rateOn) {
        if (rateOn <= 50) return 0.72f;
        if (rateOn <= 460) return 0.96f;
        if (rateOn >= 850) return 0.25f;
        return (float) (0.96 - 0.71 * (rateOn - 460.0) / (850.0 - 460.0));
    }

    private static float scoreSleep(double sleepPct, double rateOff) {
        float s1 = (float) Math.max(0.15, Math.min(1.0, sleepPct / 80.0));
        float s2 = rateOff <= 10 ? 0.75f : (rateOff <= 65 ? 0.98f : (rateOff >= 220 ? 0.20f : (float) (0.98 - 0.78 * (rateOff - 65.0) / 155.0)));
        return 0.5f * s1 + 0.5f * s2;
    }

    private static float scoreRadio(double weakSigSec, double radioMah) {
        double penalty = (weakSigSec / 3600.0) * 0.08 + (radioMah / 700.0) * 0.65;
        return (float) Math.max(0.20, Math.min(0.96, 0.96 - penalty));
    }

    private static float getLiveThermalScore(Context context) {
        try {
            Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                double tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 340) / 10.0;
                if (tempC <= 33.0) return 0.95f;
                if (tempC >= 43.0) return 0.30f;
                return (float) (0.95 - 0.65 * (tempC - 33.0) / 10.0);
            }
        } catch (Throwable ignored) {}
        return 0.80f;
    }

    private static void buildHardwareTelemetry(Context context, AnalysisResult res) {
        StringBuilder full = new StringBuilder();
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();

        String renderer = getSystemProp("debug.hwui.renderer", "");
        res.vulkanActive = "skiavk".equalsIgnoreCase(renderer);
        left.append(PerfMonitor.getQuickClusterSummary()).append("\n");

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            double totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
            double availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
            String ramPlus = safeGlobal(context.getContentResolver(), "ram_expand_size");
            left.append(String.format(Locale.US, "RAM: %.1f / %.1f GB\n", availGb, totalGb));
            if (ramPlus != null) {
                left.append("RAM Plus: ").append("0".equals(ramPlus) ? "0 GB (OFF)" : ramPlus + " GB").append("\n");
            }
        }
        left.append("GPU API: ").append(res.vulkanActive ? "Vulkan (skiavk)" : (renderer.isEmpty() ? "Padrão (HWUI)" : renderer));
        res.hardwareTileLeft = left.toString().trim();

        Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            double tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0;
            int voltMv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String src = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                    : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Sem fio" : "Bat.";
            right.append(String.format(Locale.US, "Carga: %d%% (%s) | %.1f °C\n", level, src, tempC));
            right.append(String.format(Locale.US, "Tensão: %d mV\n", voltMv));
            full.append(String.format(Locale.US, "Bateria: %d%% (%s) | %.1f °C | %d mV\n", level, src, tempC, voltMv));
        }
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            double totalGb = stat.getTotalBytes() / (1024.0 * 1024.0 * 1024.0);
            double availGb = stat.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
            right.append(String.format(Locale.US, "Armaz.: %.0f / %.0f GB\n", availGb, totalGb));
        } catch (Throwable ignored) {}

        DumpHelper.BatteryHealthInfo health = DumpHelper.readLiveBatteryHealth(context);
        if (health.asocPercent > 0 && health.cyclesCount > 0) {
            right.append(String.format(Locale.US, "ASOC %d%% · %.1f ciclos", health.asocPercent, health.cyclesCount));
        } else if (health.asocPercent > 0) {
            right.append(String.format(Locale.US, "Saúde ASOC: %d%%", health.asocPercent));
        } else if (health.chargeCounterMah > 0) {
            right.append(String.format(Locale.US, "Carga Real: %d mAh", health.chargeCounterMah));
        } else {
            right.append("Saúde: N/A");
        }
        res.hardwareTileRight = right.toString().trim();

        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                String refresh = safeSecure(context.getContentResolver(), "refresh_rate_mode");
                String refreshLabel = refresh == null ? "" : ("1".equals(refresh) ? " · Adaptativa 120Hz" : " · Fixa 60Hz");
                full.append(String.format(Locale.US, "Tela: %dx%d%s", dm.widthPixels, dm.heightPixels, refreshLabel));
            }
        } catch (Throwable ignored) {}
        res.hardwareSummary = full.toString().trim();
    }

    private static String getSystemProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object val = get.invoke(null, key, def);
            return val != null ? val.toString() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void buildAuditAndScore(Context context, AnalysisResult res) {
        ContentResolver cr = context.getContentResolver();
        StringBuilder audit = new StringBuilder();
        int passed = 0;
        int total = 0;

        String lowHeat = safeGlobal(cr, "sem_low_heat_mode");
        res.hasLowHeatSetting = lowHeat != null;
        res.lowHeatActive = "1".equals(lowHeat);
        if (lowHeat != null) {
            total++;
            if (res.lowHeatActive) { passed++; audit.append("✅ Perfil Leve (CPU eficiente): Ativo\n"); }
            else { audit.append("⚠️ Perfil Leve: DESATIVADO (reative em Bateria)\n"); }
        }

        String extraDim = safeSecure(cr, "reduce_bright_colors_activated");
        String extraDimLvlStr = safeSecure(cr, "reduce_bright_colors_level");
        int extraDimLevel = -1;
        if (extraDimLvlStr != null) {
            try { extraDimLevel = Integer.parseInt(extraDimLvlStr); } catch (NumberFormatException ignored) {}
        }
        res.hasExtraDimSetting = extraDim != null;
        res.extraDimActive = "1".equals(extraDim);
        res.extraDimLevel = extraDimLevel;
        if (extraDim != null) {
            total++;
            if (res.extraDimActive) {
                passed++;
                audit.append("✅ Brilho Extra Reduzido: Ativo");
                if (extraDimLevel >= 0) audit.append(" (intensidade ").append(extraDimLevel).append("%)");
                audit.append("\n");
            } else {
                audit.append("⚠️ Brilho Extra Reduzido: DESATIVADO (útil à noite/indoor p/ OLED)\n");
            }
        }

        String timeout = safeSystem(cr, "screen_off_timeout");
        if (timeout != null) {
            try {
                long ms = Long.parseLong(timeout);
                total++;
                if (ms <= 60000) {
                    passed++;
                    audit.append("✅ Tempo Limite de Tela: ").append(ms / 1000).append(" s\n");
                } else {
                    audit.append("⚠️ Tempo Limite de Tela: ").append(ms / 60000).append(" min (sugestão: <= 1 min)\n");
                }
            } catch (NumberFormatException ignored) {}
        }

        String aod = safeSystem(cr, "aod_mode");
        String aodNoti = safeSystem(cr, "aod_show_for_new_noti");
        if (aod != null) {
            total++;
            if ("0".equals(aod)) {
                passed++;
                audit.append("✅ Always-On Display: Desativado\n");
            } else if ("1".equals(aodNoti)) {
                passed++;
                audit.append("ℹ️ AOD: Apenas para novas notificações\n");
            } else {
                audit.append("⚠️ AOD: Sempre ativo (~0,5-1%/h)\n");
            }
        }

        String refresh = safeSecure(cr, "refresh_rate_mode");
        if (refresh != null) {
            audit.append("1".equals(refresh)
                    ? "✅ Taxa Adaptativa (LTPO/120Hz): Preservada\n"
                    : "ℹ️ Taxa de Atualização: 60 Hz fixa\n");
        }

        String ramPlus = safeGlobal(cr, "ram_expand_size");
        if (ramPlus != null) {
            total++;
            if ("0".equals(ramPlus)) { passed++; audit.append("✅ RAM Plus: Desativada (0 GB)\n"); }
            else { audit.append("⚠️ RAM Plus ativa (").append(ramPlus).append(" GB)\n"); }
        }

        String wifiScan = safeGlobal(cr, "wifi_scan_always_enabled");
        String bleScan = safeGlobal(cr, "ble_scan_always_enabled");
        if (wifiScan != null || bleScan != null) {
            total++;
            boolean ok = ("0".equals(wifiScan) || wifiScan == null) && ("0".equals(bleScan) || bleScan == null);
            if (ok) { passed++; audit.append("✅ Varreduras 2º plano (Wi-Fi/BLE): Desativadas\n"); }
            else { audit.append("⚠️ Varredura Wi-Fi ou BLE em 2º plano ativa\n"); }
        }

        String nearby = safeSystem(cr, "nearby_scanning_enabled");
        if (nearby != null) {
            total++;
            if ("0".equals(nearby)) { passed++; audit.append("✅ Busca de aparelhos próximos: Desativada\n"); }
            else { audit.append("⚠️ Busca de aparelhos próximos ativa\n"); }
        }

        String protectBat = safeGlobal(cr, "protect_battery");
        if (protectBat != null) {
            audit.append("0".equals(protectBat)
                    ? "ℹ️ Proteção de Carga: Desligada\n"
                    : "✅ Proteção de Carga: Ativa (modo " + protectBat + ")\n");
        }

        if (res.vulkanActive) {
            audit.append("✅ Renderizador GPU: Vulkan (skiavk) ativo\n");
        } else {
            audit.append("ℹ️ Renderizador GPU: Padrão (HWUI)\n");
        }

        String netMode = safeGlobal(cr, "preferred_network_mode");
        if (netMode != null) {
            if (netMode.contains("26") || netMode.contains("27")) {
                audit.append("⚠️ Rede Móvel (").append(netMode).append("): 5G NR ativo (use LTE/4G se o sinal oscilar)\n");
            } else {
                audit.append("✅ Rede Móvel (").append(netMode).append("): Sem 5G NR agressivo\n");
            }
        }

        boolean hasDump = context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
        boolean hasBat = context.checkSelfPermission("android.permission.BATTERY_STATS") == PackageManager.PERMISSION_GRANTED;
        boolean hasStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        boolean hasUsage = hasUsageStatsPermission(context);
        boolean hasNotif = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        boolean unrestrictedBat = isBatteryUnrestricted(context);

        res.coreDumpPermissionsOk = hasDump && hasBat;

        StringBuilder permSb = new StringBuilder();
        permSb.append(hasDump ? "✅" : "❌").append(" DUMP  |  ");
        permSb.append(hasBat ? "✅" : "❌").append(" BATTERY_STATS\n");
        permSb.append(hasUsage ? "✅" : "⚠️").append(" Uso de Apps  |  ");
        permSb.append(hasStorage ? "✅" : "⚠️").append(" Todos os Arquivos\n");
        permSb.append(hasNotif ? "✅" : "⚠️").append(" Notificações  |  ");
        permSb.append(unrestrictedBat ? "✅" : "⚠️").append(" Bateria Não Restrita");
        if (!hasDump || !hasBat) {
            permSb.append("\n\nℹ️ Android restringe DUMP e BATTERY_STATS ao usuário comum. Use o botão abaixo para copiar o comando e rodar uma única vez no aShell (com Shizuku) ou via ADB no PC.");
        }
        res.permissionStatusText = permSb.toString();

        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            res.autoJobArmed = (js != null && js.getPendingJob(ChargingJobService.JOB_ID_ON_CHARGE) != null);
        } catch (Throwable ignored) {}

        total++;
        if (hasDump && hasBat && hasStorage && hasUsage) {
            passed++;
            audit.append("✅ Permissões Omnia: Todas ativas\n");
        } else {
            audit.append("❌ Permissões ADB/Shizuku pendentes (veja quadro acima)\n");
        }

        total++;
        if (unrestrictedBat && res.autoJobArmed) {
            passed++;
            audit.append("✅ Coleta Passiva ao Plugar: Bateria irrestrita + Job armado");
        } else if (unrestrictedBat) {
            passed++;
            audit.append("ℹ️ Bateria Irrestrita OK · Job aguardando recarga");
        } else {
            audit.append("⚠️ Bateria do Omnia: Coloque em 'Não restrita'");
        }

        res.settingsScorePct = total > 0 ? Math.round(100f * passed / total) : 80;
        res.auditReport = audit.toString().trim();
    }

    private static String safeGlobal(ContentResolver cr, String key) {
        try { return Settings.Global.getString(cr, key); } catch (Throwable ignored) { return null; }
    }

    private static String safeSystem(ContentResolver cr, String key) {
        try { return Settings.System.getString(cr, key); } catch (Throwable ignored) { return null; }
    }

    private static String safeSecure(ContentResolver cr, String key) {
        try { return Settings.Secure.getString(cr, key); } catch (Throwable ignored) { return null; }
    }

    private static boolean isBatteryUnrestricted(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasUsageStatsPermission(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return context.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return context.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
        }
    }

    private static String formatDuration(double seconds) {
        int totalMins = (int) Math.round(Math.max(0, seconds) / 60.0);
        int h = totalMins / 60;
        int m = totalMins % 60;
        return String.format(Locale.US, "%dh%02dm", h, m);
    }

    private static String formatDate(long ts) {
        try {
            return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(ts));
        } catch (Exception e) {
            return "-";
        }
    }

    private static String formatShortDate(long ts) {
        try {
            return new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(ts));
        } catch (Exception e) {
            return "-";
        }
    }
}

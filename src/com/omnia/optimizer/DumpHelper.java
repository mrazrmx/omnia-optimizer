package com.omnia.optimizer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DumpHelper {
    private static final String TAG = "OmniaDumpHelper";

    public static class FastSignature {
        public String startClockTime = "";
        public double totalDischargeMah = 0;
    }

    public static class BatteryHealthInfo {
        public int asocPercent = -1;
        public double cyclesCount = -1;
        public int chargeCounterMah = -1;
        public String firstUseDate = "";
        public long readTimestampMs = 0;
    }

    private static BatteryHealthInfo cachedHealth = null;

    public static FastSignature readFastSignature() {
        FastSignature sig = new FastSignature();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            final IBinder binder = (IBinder) getService.invoke(null, "batterystats");
            if (binder == null) return sig;

            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        binder.dump(pipe[1].getFileDescriptor(), new String[0]);
                    } catch (Throwable ignored) {
                    } finally {
                        try { pipe[1].close(); } catch (Throwable ignored) {}
                    }
                }
            });
            writer.start();

            BufferedReader br = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])));
            String line;
            boolean inStats = false;
            while ((line = br.readLine()) != null) {
                if (!inStats) {
                    if (line.startsWith("Statistics since last charge:")) {
                        inStats = true;
                    }
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("Discharge:") && sig.totalDischargeMah == 0) {
                    Matcher m = Pattern.compile("Discharge:\\s*([0-9.]+)").matcher(trimmed);
                    if (m.find()) sig.totalDischargeMah = Double.parseDouble(m.group(1));
                } else if (trimmed.startsWith("Start clock time:")) {
                    sig.startClockTime = trimmed.substring("Start clock time:".length()).trim();
                }
                if (sig.totalDischargeMah > 0 && !sig.startClockTime.isEmpty()) {
                    break;
                }
            }
            br.close();
        } catch (Throwable t) {
            Log.d(TAG, "readFastSignature erro", t);
        }
        return sig;
    }

    public static synchronized BatteryHealthInfo readLiveBatteryHealth(Context context) {
        long now = System.currentTimeMillis();
        if (cachedHealth != null && (now - cachedHealth.readTimestampMs) < 30000L) {
            return cachedHealth;
        }
        String batDump = dumpServiceText("battery");
        BatteryHealthInfo info = parseBatteryHealthText(context, batDump);
        info.readTimestampMs = now;
        cachedHealth = info;
        return info;
    }

    public static BatteryHealthInfo parseBatteryHealthText(Context context, String batText) {
        BatteryHealthInfo info = new BatteryHealthInfo();
        if (batText != null && !batText.isEmpty()) {
            Matcher mAsoc = Pattern.compile("mSavedBatteryAsoc:\\s*\\[(\\d+)\\]").matcher(batText);
            if (!mAsoc.find()) {
                mAsoc = Pattern.compile("AsocData\\s+efsValue:(\\d+)").matcher(batText);
            }
            if (mAsoc.find()) {
                try { info.asocPercent = Integer.parseInt(mAsoc.group(1)); } catch (NumberFormatException ignored) {}
            }

            Matcher mUsage = Pattern.compile("mSavedBatteryUsage:\\s*\\[(\\d+)\\]").matcher(batText);
            if (!mUsage.find()) {
                mUsage = Pattern.compile("DischargeLevelData\\s+efsValue:(\\d+)").matcher(batText);
            }
            if (mUsage.find()) {
                try { info.cyclesCount = Integer.parseInt(mUsage.group(1)) / 100.0; } catch (NumberFormatException ignored) {}
            }

            Matcher mFirstUse = Pattern.compile("FirstUseDate(?:Data\\s+efsValue)?\\s*:\\s*\\[?(\\d{8})\\]?").matcher(batText);
            if (mFirstUse.find()) {
                String raw = mFirstUse.group(1);
                info.firstUseDate = raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
            }

            Matcher mCc = Pattern.compile("(?i)Charge counter:\\s*(\\d+)").matcher(batText);
            if (mCc.find()) {
                try {
                    long raw = Long.parseLong(mCc.group(1));
                    info.chargeCounterMah = (int) Math.round(raw > 20000 ? raw / 1000.0 : raw);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (info.chargeCounterMah <= 0 && context != null) {
            try {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (cc > 0) {
                        info.chargeCounterMah = (int) Math.round(cc > 20000 ? cc / 1000.0 : cc);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return info;
    }

    public static String dumpServiceText(String serviceName) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            final IBinder binder = (IBinder) getService.invoke(null, serviceName);
            if (binder == null) return "";

            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        binder.dump(pipe[1].getFileDescriptor(), new String[0]);
                    } catch (Throwable ignored) {
                    } finally {
                        try { pipe[1].close(); } catch (Throwable ignored) {}
                    }
                }
            });
            writer.start();

            BufferedReader br = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            writer.join(600);
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static class AppUsageEntry {
        String name;
        double mah;
        AppUsageEntry(String name, double mah) {
            this.name = name;
            this.mah = mah;
        }
    }

    public static String performCollection(Context context, String userNote) {
        StringBuilder log = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US);
        String timestamp = sdf.format(new Date());

        File targetDir = new File(StorageManager.BASE_DIR_PATH, "checkup-" + timestamp);
        targetDir.mkdirs();

        log.append("Destino: ").append(targetDir.getAbsolutePath()).append("\n");

        dumpServiceDirect("battery", new String[0], new File(targetDir, "battery.txt"), log);
        dumpBatteryManagerInfo(context, new File(targetDir, "battery.txt"));
        dumpServiceDirect("power", new String[0], new File(targetDir, "power.txt"), log);
        dumpServiceDirect("deviceidle", new String[0], new File(targetDir, "deviceidle.txt"), log);
        dumpServiceDirect("display", new String[0], new File(targetDir, "display.txt"), log);
        dumpServiceDirect("batterystats", new String[0], new File(targetDir, "batterystats.txt"), log);
        dumpServiceDirect("batterystats", new String[]{"--history"}, new File(targetDir, "history.txt"), log);

        copySystemFile("/proc/uptime", new File(targetDir, "uptime.txt"));
        copySystemFile("/proc/sys/kernel/random/boot_id", new File(targetDir, "boot_id.txt"));

        writeFile(new File(targetDir, "build.txt"), Build.MODEL + " · " + Build.DISPLAY + "\n");
        SimpleDateFormat sdfHuman = new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US);
        String tempoContent = sdfHuman.format(new Date()) + "\n" +
                readFile("/proc/uptime").trim() + "\n" +
                readFile("/proc/sys/kernel/random/boot_id").trim() + "\n" +
                Build.DISPLAY + "\n";
        writeFile(new File(targetDir, "tempo.txt"), tempoContent);

        ContentResolver cr = context.getContentResolver();
        String lowHeat = safeGlobal(cr, "sem_low_heat_mode");
        String aod = safeSystem(cr, "aod_mode");
        String timeout = safeSystem(cr, "screen_off_timeout");
        String refresh = safeSecure(cr, "refresh_rate_mode");
        String ramPlus = safeGlobal(cr, "ram_expand_size");
        String protectBat = safeGlobal(cr, "protect_battery");

        String configContent = "sem_low_heat_mode=" + (lowHeat != null ? lowHeat : "") + "\n" +
                "aod_mode=" + (aod != null ? aod : "") + "\n" +
                "screen_off_timeout=" + (timeout != null ? timeout : "") + "\n" +
                "refresh_rate_mode=" + (refresh != null ? refresh : "") + "\n" +
                "ram_expand_size=" + (ramPlus != null ? ramPlus : "") + "\n" +
                "protect_battery=" + (protectBat != null ? protectBat : "") + "\n";
        writeFile(new File(targetDir, "config-atual.txt"), configContent);

        if (userNote != null && !userNote.trim().isEmpty()) {
            writeFile(new File(targetDir, "contexto-usuario.txt"), userNote.trim() + "\n");
        }

        try {
            CycleData cycle = parseCycleData(context, targetDir, timestamp, userNote);
            WakelockSentinel.evaluateCollectedCycle(context, cycle, targetDir);
            CycleData.saveCycle(cycle);
            log.append("Métricas consolidadas em historico_vitalicio.json\n");
        } catch (Throwable t) {
            Log.e(TAG, "Erro ao analisar dados do ciclo", t);
        }

        try {
            StorageManager.cleanupOldRawDumps(14);
        } catch (Throwable ignored) {}

        log.append("Coleta concluída com sucesso em ").append(timestamp).append("\n");
        return log.toString();
    }

    private static CycleData parseCycleData(Context context, File dir, String id, String userNote) {
        CycleData d = new CycleData();
        d.id = id;
        d.timestamp = System.currentTimeMillis();
        d.userContext = userNote != null ? userNote : "";
        d.bootId = readFile(new File(dir, "boot_id.txt").getAbsolutePath()).trim();
        if (d.bootId.isEmpty()) {
            d.bootId = readFile("/proc/sys/kernel/random/boot_id").trim();
        }

        File batFile = new File(dir, "battery.txt");
        if (batFile.exists()) {
            String batText = readAllFile(batFile.getAbsolutePath());
            Matcher mLevel = Pattern.compile("(?m)^\\s*level:\\s*(\\d+)").matcher(batText);
            if (mLevel.find()) d.batteryLevel = Integer.parseInt(mLevel.group(1));

            Matcher mTemp = Pattern.compile("(?m)^\\s*temperature:\\s*(\\d+)").matcher(batText);
            if (mTemp.find()) d.batteryTempC = Integer.parseInt(mTemp.group(1)) / 10.0;

            BatteryHealthInfo health = parseBatteryHealthText(context, batText);
            d.asocPercent = health.asocPercent;
            d.batteryCyclesCount = health.cyclesCount;
            d.chargeCounterMah = health.chargeCounterMah;
        }

        File bsFile = new File(dir, "batterystats.txt");
        List<AppUsageEntry> appList = new ArrayList<AppUsageEntry>();
        List<String> topWakelocks = new ArrayList<String>();
        double totalDischarge = 0;
        if (bsFile.exists()) {
            boolean inSinceLastCharge = false;
            boolean inBrightnessBlock = false;
            boolean inRefreshBlock = false;
            boolean inGlobalPower = false;
            boolean finishedGlobalPower = false;
            boolean inPartialWakelocks = false;
            boolean finishedPartialWakelocks = false;
            double directScreenOnDischarge = 0;

            try {
                BufferedReader br = new BufferedReader(new FileReader(bsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    if (!inSinceLastCharge) {
                        if (line.startsWith("Statistics since last charge:")) {
                            inSinceLastCharge = true;
                        }
                        continue;
                    }

                    String trimmed = line.trim();

                    if (trimmed.startsWith("Screen brightnesses:")) {
                        inBrightnessBlock = true;
                        continue;
                    } else if (inBrightnessBlock && !line.startsWith("    ")) {
                        inBrightnessBlock = false;
                    }

                    if (trimmed.startsWith("Screen refresh rate:")) {
                        inRefreshBlock = true;
                        continue;
                    } else if (inRefreshBlock && !line.startsWith("    ")) {
                        inRefreshBlock = false;
                    }

                    if (trimmed.equals("Global")) {
                        inGlobalPower = true;
                        continue;
                    } else if (inGlobalPower && (trimmed.startsWith("(on battery") || trimmed.startsWith("UID "))) {
                        inGlobalPower = false;
                        finishedGlobalPower = true;
                    }

                    if (trimmed.equals("All partial wake locks:") && !finishedPartialWakelocks) {
                        inPartialWakelocks = true;
                        continue;
                    } else if (inPartialWakelocks) {
                        if (trimmed.startsWith("Wake lock ")) {
                            if (topWakelocks.size() < 3) {
                                String parsedWl = formatWakelockEntry(context, trimmed);
                                if (parsedWl != null) topWakelocks.add(parsedWl);
                            }
                            continue;
                        } else if (!trimmed.isEmpty()) {
                            inPartialWakelocks = false;
                            finishedPartialWakelocks = true;
                        }
                    }

                    if (trimmed.startsWith("System starts:")) {
                        Matcher mStarts = Pattern.compile("System starts:\\s*(\\d+)").matcher(trimmed);
                        if (mStarts.find()) {
                            d.systemStarts = Integer.parseInt(mStarts.group(1));
                        }
                    } else if (trimmed.startsWith("Start clock time:") && d.startClockTime.isEmpty()) {
                        d.startClockTime = trimmed.substring("Start clock time:".length()).trim();
                    } else if (trimmed.startsWith("Screen on:") && d.screenOnSeconds == 0) {
                        d.screenOnSeconds = parseDurationSeconds(trimmed);
                    } else if (trimmed.startsWith("Time on battery screen off:") && d.screenOffSeconds == 0) {
                        int rtIdx = trimmed.indexOf("realtime,");
                        if (rtIdx > 0) {
                            d.screenOffSeconds = parseDurationSeconds(trimmed.substring(0, rtIdx));
                            double uptimeOff = parseSecondDurationSeconds(trimmed);
                            d.deepSleepSeconds = Math.max(0, d.screenOffSeconds - uptimeOff);
                            if (d.screenOffSeconds > 0) {
                                d.deepSleepPercent = 100.0 * d.deepSleepSeconds / d.screenOffSeconds;
                            }
                        } else {
                            d.screenOffSeconds = parseDurationSeconds(trimmed);
                        }
                    } else if (trimmed.startsWith("Time on battery screen doze:") && d.screenDozeSeconds == 0) {
                        d.screenDozeSeconds = parseDurationSeconds(trimmed);
                    } else if (trimmed.startsWith("Discharge:") && totalDischarge == 0) {
                        Matcher m = Pattern.compile("Discharge:\\s*([0-9.]+)").matcher(trimmed);
                        if (m.find()) {
                            totalDischarge = Double.parseDouble(m.group(1));
                        }
                    } else if (trimmed.startsWith("Screen off discharge:") && d.dischargeOffMah == 0) {
                        Matcher m = Pattern.compile("Screen off discharge:\\s*([0-9.]+)").matcher(trimmed);
                        if (m.find()) {
                            d.dischargeOffMah = Double.parseDouble(m.group(1));
                        }
                    } else if (trimmed.startsWith("Screen doze discharge:") && d.screenDozeDischargeMah == 0) {
                        Matcher m = Pattern.compile("Screen doze discharge:\\s*([0-9.]+)").matcher(trimmed);
                        if (m.find()) {
                            d.screenDozeDischargeMah = Double.parseDouble(m.group(1));
                        }
                    } else if (trimmed.startsWith("Screen on discharge:") && directScreenOnDischarge == 0) {
                        Matcher m = Pattern.compile("Screen on discharge:\\s*([0-9.]+)").matcher(trimmed);
                        if (m.find()) {
                            directScreenOnDischarge = Double.parseDouble(m.group(1));
                        }
                    } else if (trimmed.startsWith("Weak signal time:") && d.weakSignalSeconds == 0) {
                        d.weakSignalSeconds = parseDurationSeconds(trimmed);
                    } else if (trimmed.startsWith("Mobile active time:") && d.mobileActiveSeconds == 0) {
                        d.mobileActiveSeconds = parseDurationSeconds(trimmed);
                    } else if (trimmed.startsWith("Mobile active 5G time:") && d.mobile5gSeconds == 0) {
                        d.mobile5gSeconds = parseDurationSeconds(trimmed);
                    } else if (trimmed.startsWith("Total partial wakelock time:") && d.wakelockSeconds == 0) {
                        d.wakelockSeconds = parseDurationSeconds(trimmed);
                    } else if (inBrightnessBlock && (trimmed.startsWith("light ") || trimmed.startsWith("bright "))) {
                        Matcher m = Pattern.compile("\\(([0-9,.]+)\\s*%\\)").matcher(trimmed);
                        if (m.find()) {
                            d.lightBrightnessPercent += Double.parseDouble(m.group(1).replace(",", "."));
                        }
                    } else if (inRefreshBlock && trimmed.startsWith("high ")) {
                        d.highRefreshSeconds = parseDurationSeconds(trimmed);
                    } else if (inGlobalPower && !finishedGlobalPower) {
                        if (trimmed.startsWith("cpu:")) d.cpuMah = extractFirstNumber(trimmed);
                        else if (trimmed.startsWith("video:")) d.videoMah = extractFirstNumber(trimmed);
                        else if (trimmed.startsWith("camera:")) d.cameraMah = extractFirstNumber(trimmed);
                        else if (trimmed.startsWith("audio:")) d.audioMah = extractFirstNumber(trimmed);
                        else if (trimmed.startsWith("mobile_radio:")) d.mobileRadioMah = extractFirstNumber(trimmed);
                        else if (trimmed.startsWith("gnss:")) {
                            d.gnssMah = extractFirstNumber(trimmed);
                            int durIdx = trimmed.indexOf("duration:");
                            if (durIdx >= 0) d.gnssSeconds = parseDurationSeconds(trimmed.substring(durIdx));
                        }
                    } else if (trimmed.startsWith("UID ")) {
                        Matcher m = Pattern.compile("UID\\s+([^:]+):\\s*([0-9.]+)").matcher(trimmed);
                        if (m.find()) {
                            String uid = m.group(1);
                            double mah = Double.parseDouble(m.group(2));
                            if (mah > 5.0 && !uid.equals("1000") && !uid.equals("0") && !uid.equals("1001")) {
                                appList.add(new AppUsageEntry(resolveUidName(context, uid), mah));
                            }
                        }
                    }
                }
                br.close();
            } catch (Exception ignored) {}

            if (directScreenOnDischarge > 0) {
                d.dischargeOnMah = directScreenOnDischarge;
            } else if (totalDischarge > 0) {
                d.dischargeOnMah = Math.max(0, totalDischarge - d.dischargeOffMah);
            }

            if (!topWakelocks.isEmpty()) {
                StringBuilder wlSb = new StringBuilder();
                for (int i = 0; i < topWakelocks.size(); i++) {
                    if (i > 0) wlSb.append(" · ");
                    wlSb.append(topWakelocks.get(i));
                }
                d.topWakelocksSummary = wlSb.toString();
            }

            if (!appList.isEmpty()) {
                Collections.sort(appList, new Comparator<AppUsageEntry>() {
                    @Override
                    public int compare(AppUsageEntry a, AppUsageEntry b) {
                        return Double.compare(b.mah, a.mah);
                    }
                });
                d.topApp = appList.get(0).name;
                d.topAppMah = appList.get(0).mah;
                StringBuilder topSb = new StringBuilder();
                int max = Math.min(3, appList.size());
                for (int i = 0; i < max; i++) {
                    if (i > 0) topSb.append(" · ");
                    topSb.append(String.format(Locale.US, "%s (%.0f mAh)", appList.get(i).name, appList.get(i).mah));
                }
                d.topAppsSummary = topSb.toString();
            }
        }

        if (d.screenOnSeconds > 60) {
            d.rateOnMahH = d.dischargeOnMah / (d.screenOnSeconds / 3600.0);
        }
        if (d.screenOffSeconds > 60) {
            d.rateOffMahH = d.dischargeOffMah / (d.screenOffSeconds / 3600.0);
        }

        classifyCycleIntegrity(d, totalDischarge);
        return d;
    }

    private static void classifyCycleIntegrity(CycleData d, double currentTotalDischarge) {
        if (d.startClockTime != null && !d.startClockTime.isEmpty()) {
            List<CycleData> prior = CycleData.loadAllCycles();
            for (CycleData prev : prior) {
                if (!d.startClockTime.equals(prev.startClockTime)) continue;
                if (prev.hasPartialRecharge) {
                    d.hasPartialRecharge = true;
                    break;
                }
                if (d.batteryLevel > 0 && prev.batteryLevel > 0 && d.batteryLevel > prev.batteryLevel + 1) {
                    d.hasPartialRecharge = true;
                    break;
                }
                double prevTotal = prev.dischargeOnMah + prev.dischargeOffMah;
                if (currentTotalDischarge > 0 && prevTotal > 0 && currentTotalDischarge + 25.0 < prevTotal) {
                    d.hasPartialRecharge = true;
                    break;
                }
            }
        }

        if (d.systemStarts > 0 && d.hasPartialRecharge) {
            d.integrityStatus = "MISTO";
            d.integrityReason = d.systemStarts + " reinício(s) + recarga parcial";
        } else if (d.systemStarts > 0) {
            d.integrityStatus = "MISTO";
            d.integrityReason = d.systemStarts + " reinício(s) na janela";
        } else if (d.hasPartialRecharge) {
            d.integrityStatus = "MISTO";
            d.integrityReason = "Recarga parcial na mesma janela";
        } else {
            d.integrityStatus = "LIMPO";
            d.integrityReason = "Janela contínua sem reinício/recarga parcial";
        }
    }

    private static String formatWakelockEntry(Context context, String trimmedLine) {
        Matcher m = Pattern.compile("^Wake lock\\s+([^\\s]+)\\s+(.+?):\\s+(\\d+[hms][^(\\r\\n]*)\\s*\\(").matcher(trimmedLine);
        if (!m.find()) return null;
        String uid = m.group(1);
        String tag = cleanWakelockTag(m.group(2));
        double sec = parseDurationSeconds(m.group(3));
        if (sec < 30.0) return null;
        String appName = "1000".equals(uid) ? "Sistema" : ("1001".equals(uid) ? "Rádio" : resolveUidName(context, uid));
        return String.format(Locale.US, "%s (%s %s)", appName, tag, formatShortDuration(sec));
    }

    public static String cleanWakelockTag(String raw) {
        String s = raw.replace("*job*e//", "").replace("*job*r//", "").replace("*job*e/", "").replace("*job*r/", "");
        int atIdx = s.indexOf("/@");
        if (atIdx > 0) s = s.substring(0, atIdx);
        int slashIdx = s.lastIndexOf('/');
        if (slashIdx > 0 && slashIdx < s.length() - 1 && s.contains("androidx.")) {
            s = s.substring(0, slashIdx);
        }
        int colonIdx = s.indexOf(':');
        if (colonIdx > 0 && s.startsWith("com.")) {
            s = s.substring(colonIdx + 1);
        }
        if (s.length() > 20) s = s.substring(0, 17) + "...";
        return s.trim();
    }

    private static String formatShortDuration(double seconds) {
        int mins = (int) Math.round(seconds / 60.0);
        if (mins >= 60) return String.format(Locale.US, "%dh%02dm", mins / 60, mins % 60);
        return Math.max(1, mins) + "m";
    }

    public static String formatShortDurationExact(double seconds) {
        int totalSec = (int) Math.round(Math.max(0.0, seconds));
        if (totalSec >= 3600) {
            return String.format(Locale.US, "%dh%02dm", totalSec / 3600, (totalSec % 3600) / 60);
        }
        if (totalSec >= 60) {
            return String.format(Locale.US, "%dm%02ds", totalSec / 60, totalSec % 60);
        }
        return totalSec + "s";
    }

    private static double extractFirstNumber(String line) {
        Matcher m = Pattern.compile(":\\s*([0-9.]+)").matcher(line);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    public static String resolveUidName(Context context, String uidStr) {
        if (context == null || uidStr == null) return uidStr;
        try {
            int uid = -1;
            Matcher m = Pattern.compile("u0a(\\d+)").matcher(uidStr);
            if (m.find()) {
                uid = 10000 + Integer.parseInt(m.group(1));
            } else if (uidStr.matches("\\d+")) {
                uid = Integer.parseInt(uidStr);
            }
            if (uid > 0) {
                PackageManager pm = context.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) {
                    ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (label != null) {
                        return label.toString();
                    }
                    return pkgs[0];
                }
            }
        } catch (Throwable ignored) {}
        return uidStr;
    }

    public static double parseDurationSeconds(String line) {
        double total = 0;
        Matcher mH = Pattern.compile("(\\d+)h").matcher(line);
        if (mH.find()) total += Double.parseDouble(mH.group(1)) * 3600;
        Matcher mM = Pattern.compile("(\\d+)m").matcher(line);
        if (mM.find()) total += Double.parseDouble(mM.group(1)) * 60;
        Matcher mS = Pattern.compile("(\\d+)s").matcher(line);
        if (mS.find()) total += Double.parseDouble(mS.group(1));
        return total;
    }

    private static double parseSecondDurationSeconds(String line) {
        int rtIdx = line.indexOf("realtime,");
        int upIdx = line.indexOf("uptime");
        if (rtIdx >= 0 && upIdx > rtIdx) {
            return parseDurationSeconds(line.substring(rtIdx + "realtime,".length(), upIdx));
        }
        return 0;
    }

    private static void dumpServiceDirect(String serviceName, String[] args, File outputFile, StringBuilder log) {
        boolean success = false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, serviceName);
            if (binder != null) {
                FileOutputStream fos = new FileOutputStream(outputFile);
                binder.dump(fos.getFD(), args != null ? args : new String[0]);
                fos.flush();
                fos.close();
                if (outputFile.length() > 0) {
                    success = true;
                    log.append("OK: ").append(serviceName).append(" (").append(outputFile.length()).append(" bytes)\n");
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Dump failed for " + serviceName, t);
        }

        if (!success) {
            try {
                String cmd = "dumpsys " + serviceName;
                if (args != null && args.length > 0) {
                    for (String a : args) cmd += " " + a;
                }
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                FileOutputStream fos = new FileOutputStream(outputFile);
                InputStream is = p.getInputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.flush();
                fos.close();
                p.waitFor();
                if (outputFile.length() > 0) {
                    log.append("OK (Shell): ").append(serviceName).append("\n");
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void dumpBatteryManagerInfo(Context context, File outputFile) {
        if (outputFile.exists() && outputFile.length() > 50) return;
        try {
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                int temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                String tech = batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

                StringBuilder sb = new StringBuilder();
                sb.append("Current Battery Service state:\n");
                sb.append("  AC powered: ").append(plugged == BatteryManager.BATTERY_PLUGGED_AC).append("\n");
                sb.append("  USB powered: ").append(plugged == BatteryManager.BATTERY_PLUGGED_USB).append("\n");
                sb.append("  Wireless powered: ").append(plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS).append("\n");
                sb.append("  status: ").append(status).append("\n");
                sb.append("  health: ").append(health).append("\n");
                sb.append("  present: true\n");
                sb.append("  level: ").append(level).append("\n");
                sb.append("  scale: ").append(scale).append("\n");
                sb.append("  voltage: ").append(voltage).append("\n");
                sb.append("  temperature: ").append(temp).append("\n");
                sb.append("  technology: ").append(tech != null ? tech : "Li-ion").append("\n");

                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    long cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    sb.append("  charge counter: ").append(cc).append("\n");
                    sb.append("  current now: ").append(cur).append("\n");
                }
                writeFile(outputFile, sb.toString());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error reading BatteryManager", t);
        }
    }

    private static void copySystemFile(String srcPath, File destFile) {
        try {
            File src = new File(srcPath);
            if (src.exists() && src.canRead()) {
                BufferedReader reader = new BufferedReader(new FileReader(src));
                FileWriter writer = new FileWriter(destFile);
                String line;
                while ((line = reader.readLine()) != null) writer.write(line + "\n");
                reader.close();
                writer.flush();
                writer.close();
            }
        } catch (Throwable ignored) {}
    }

    private static String safeGlobal(ContentResolver cr, String key) {
        try { return Settings.Global.getString(cr, key); } catch (Throwable t) { return null; }
    }

    private static String safeSystem(ContentResolver cr, String key) {
        try { return Settings.System.getString(cr, key); } catch (Throwable t) { return null; }
    }

    private static String safeSecure(ContentResolver cr, String key) {
        try { return Settings.Secure.getString(cr, key); } catch (Throwable t) { return null; }
    }

    private static String readFile(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            br.close();
            return line != null ? line : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readAllFile(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static void writeFile(File file, String content) {
        try {
            FileWriter fw = new FileWriter(file);
            fw.write(content);
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {}
    }
}

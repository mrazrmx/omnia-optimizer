package com.omnia.optimizer;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentinela Anti-Wakelock sob demanda e pos-ciclo (0% de consumo em 2o plano).
 * Classifica locks ativos em tempo real (dumpsys power) e viloes de tela apagada (batterystats).
 */
public final class WakelockSentinel {

    public static final class SuspectApp {
        public String packageName = "";
        public String appName = "";
        public String reason = "";
        public double weightScore = 0.0;
        public boolean protectedApp = false;
        public boolean expectedMedia = false;
        public boolean inDozeUserWhitelist = false;
    }

    public static final class SentinelReport {
        public String severity = "OK"; // OK | ATENCAO | CRITICO
        public String headline = "Repouso limpo (sem ladrões detectados)";
        public String details = "";
        public String notificationSummary = "";
        public final List<SuspectApp> suspects = new ArrayList<SuspectApp>();
    }

    private static final class ActiveLockEntry {
        String packageName = "";
        String appName;
        String lockType;
        String tag;
        double ageSeconds;
        boolean isLong;
        boolean expectedMedia;
    }

    private static final class PartialLockStat {
        String packageName = "";
        String appName;
        String tag;
        double actualSeconds;
        double runningSeconds;
        boolean expectedMedia;
        boolean knownWatcher;
    }

    private static final class ScreenOffUidEntry {
        String packageName = "";
        String appName;
        boolean isSystemUid;
        double nonAudioOffMah;
        double audioOffMah;
        double wakelockSeconds;
        double cpuMah;
    }

    private WakelockSentinel() {
    }

    /**
     * Varredura instantanea sob demanda disparada pelo usuario (zero escrita em disco).
     */
    public static SentinelReport scanLiveNow(Context context) {
        String powerText = DumpHelper.dumpServiceText("power");
        String bsText = DumpHelper.dumpServiceText("batterystats");
        String idleText = DumpHelper.dumpServiceText("deviceidle");
        return analyzeTexts(context, null, powerText, bsText, idleText, true);
    }

    /**
     * Checagem ultra-rapida (~15ms) somente de locks segurando CPU no instante via dumpsys power.
     */
    public static SentinelReport scanPowerQuick(Context context) {
        String powerText = DumpHelper.dumpServiceText("power");
        List<ActiveLockEntry> activeLocks = parseActivePowerLocks(context, powerText);
        Collections.sort(activeLocks, new Comparator<ActiveLockEntry>() {
            @Override
            public int compare(ActiveLockEntry a, ActiveLockEntry b) {
                return Double.compare(b.ageSeconds, a.ageSeconds);
            }
        });
        SentinelReport rep = new SentinelReport();
        boolean crit = false;
        boolean att = false;
        String culprit = "";
        for (ActiveLockEntry e : activeLocks) {
            boolean suspicious = !e.expectedMedia && (e.isLong || e.ageSeconds >= 20.0);
            if (suspicious) {
                att = true;
                if (e.ageSeconds >= 180.0 || e.isLong) crit = true;
                if (culprit.isEmpty()) {
                    culprit = e.appName + " (" + e.tag + " · " + DumpHelper.formatShortDurationExact(e.ageSeconds) + ")";
                }
            }
        }
        rep.severity = crit ? "CRITICO" : (att ? "ATENCAO" : "OK");
        rep.headline = culprit.isEmpty()
                ? "LIMPO AGORA: Nenhum lock longo ativo."
                : (crit ? "CRÍTICO · Ativo: " : "ATENÇÃO · Ativo: ") + culprit;
        return rep;
    }

    /**
     * Avaliacao pos-coleta usando os arquivos ja gravados no ciclo (custo zero extra de Binder).
     */
    public static SentinelReport evaluateCollectedCycle(Context context, CycleData cycle, File checkupDir) {
        String powerText = readFileIfExists(new File(checkupDir, "power.txt"));
        String bsText = readFileIfExists(new File(checkupDir, "batterystats.txt"));
        String idleText = readFileIfExists(new File(checkupDir, "deviceidle.txt"));
        SentinelReport rep = analyzeTexts(context, cycle, powerText, bsText, idleText, false);
        if (cycle != null) {
            cycle.sentinelSeverity = rep.severity;
            cycle.sentinelSummary = rep.headline + (rep.notificationSummary.isEmpty() ? "" : "\n" + rep.notificationSummary);
        }
        return rep;
    }

    private static SentinelReport analyzeTexts(Context context, CycleData cycle,
                                               String powerText, String bsText, String idleText, boolean liveMode) {
        SentinelReport rep = new SentinelReport();
        if ((powerText == null || powerText.trim().isEmpty()) && (bsText == null || bsText.trim().isEmpty())) {
            rep.severity = "ATENCAO";
            rep.headline = "Permissão DUMP / BATTERY_STATS pendente";
            rep.details = "Conceda DUMP e BATTERY_STATS (ou use Shizuku Auto-Ativar na aba OTIM) para habilitar o Sentinela.";
            return rep;
        }
        Set<String> userDozeWhitelist = parseUserDozeWhitelist(idleText);
        List<ActiveLockEntry> activeLocks = parseActivePowerLocks(context, powerText);
        List<PartialLockStat> partialStats = new ArrayList<PartialLockStat>();
        List<ScreenOffUidEntry> offUids = new ArrayList<ScreenOffUidEntry>();
        parseBatteryStatsScreenOff(context, bsText, partialStats, offUids);

        Collections.sort(activeLocks, new Comparator<ActiveLockEntry>() {
            @Override
            public int compare(ActiveLockEntry a, ActiveLockEntry b) {
                return Double.compare(b.ageSeconds, a.ageSeconds);
            }
        });
        Collections.sort(partialStats, new Comparator<PartialLockStat>() {
            @Override
            public int compare(PartialLockStat a, PartialLockStat b) {
                return Double.compare(b.actualSeconds + b.runningSeconds * 10.0, a.actualSeconds + a.runningSeconds * 10.0);
            }
        });
        Collections.sort(offUids, new Comparator<ScreenOffUidEntry>() {
            @Override
            public int compare(ScreenOffUidEntry a, ScreenOffUidEntry b) {
                return Double.compare(b.nonAudioOffMah, a.nonAudioOffMah);
            }
        });

        List<String> findings = new ArrayList<String>();
        Map<String, SuspectApp> suspectMap = new LinkedHashMap<String, SuspectApp>();
        boolean critical = false;
        boolean attention = false;
        String topCulprit = "";

        // 1. Avaliar taxa liquida apagada (descontando AOD/Screen doze) se houver ciclo
        if (cycle != null && cycle.screenOffSeconds >= 1800) {
            double netOffMah = Math.max(0.0, cycle.dischargeOffMah - cycle.screenDozeDischargeMah);
            double netRateOff = netOffMah / (cycle.screenOffSeconds / 3600.0);
            double netNoAudio = Math.max(0.0, netOffMah - Math.min(netOffMah * 0.5, cycle.audioMah));
            double netNoAudioRate = netNoAudio / (cycle.screenOffSeconds / 3600.0);

            if (netNoAudioRate >= 135.0 || cycle.deepSleepPercent < 50.0) {
                critical = true;
                findings.add(String.format(Locale.US,
                        "🚨 Repouso crítico: %.0f mAh/h líquido (sem AOD/áudio) · Sono Profundo %.1f%%",
                        netNoAudioRate, cycle.deepSleepPercent));
            } else if (netRateOff >= 95.0 || cycle.deepSleepPercent < 60.0) {
                attention = true;
                findings.add(String.format(Locale.US,
                        "⚠️ Repouso elevado: %.0f mAh/h sem AOD · Sono Profundo %.1f%%",
                        netRateOff, cycle.deepSleepPercent));
            }
        }

        // 2. Active Wake Locks neste exato instante (dumpsys power)
        StringBuilder activeSb = new StringBuilder();
        int suspectActiveCount = 0;
        for (ActiveLockEntry e : activeLocks) {
            boolean suspicious = !e.expectedMedia && (e.isLong || e.ageSeconds >= 20.0);
            if (suspicious) {
                suspectActiveCount++;
                attention = true;
                if (e.ageSeconds >= 180.0 || e.isLong) critical = true;
                if (topCulprit.isEmpty()) topCulprit = e.appName + " (" + e.tag + ")";
                addSuspect(context, suspectMap, e.packageName, e.appName,
                        "Lock ativo " + DumpHelper.formatShortDurationExact(e.ageSeconds) + " (" + e.tag + ")",
                        500.0 + e.ageSeconds, e.expectedMedia);
            }
            if (e.isLong || e.ageSeconds >= 3.0) {
                String ageFmt = e.ageSeconds >= 1.0 ? DumpHelper.formatShortDurationExact(e.ageSeconds)
                        : "<1s";
                activeSb.append(String.format(Locale.US, "\n   %s %s: %s (%s%s)",
                        e.expectedMedia ? "🎵" : (suspicious ? "🚨" : "•"),
                        e.appName, e.tag, ageFmt, e.isLong ? " LONG" : ""));
            }
        }

        // 3. Partial wakelocks nocivos / rodando no batterystats
        StringBuilder partialSb = new StringBuilder();
        int suspectPartialCount = 0;
        for (PartialLockStat p : partialStats) {
            boolean runningNow = p.runningSeconds >= 2.0;
            boolean heavyNonMedia = !p.expectedMedia && (p.actualSeconds >= 900.0 || (p.knownWatcher && p.actualSeconds >= 120.0) || runningNow);
            if (heavyNonMedia) {
                suspectPartialCount++;
                if (p.actualSeconds >= 1800.0 || runningNow || p.knownWatcher) {
                    if (p.actualSeconds >= 2700.0 || runningNow) critical = true;
                    else attention = true;
                }
                if (topCulprit.isEmpty()) topCulprit = p.appName + " (" + p.tag + ")";
                addSuspect(context, suspectMap, p.packageName, p.appName,
                        "Lock " + DumpHelper.formatShortDurationExact(p.actualSeconds) + (runningNow ? " [PRESO]" : ""),
                        200.0 + p.actualSeconds / 60.0 + (runningNow ? 250.0 : 0.0), p.expectedMedia);
            }
            if (partialSb.length() < 420 && (heavyNonMedia || p.actualSeconds >= 180.0)) {
                partialSb.append(String.format(Locale.US, "\n   %s %s: %s · efetivo %s%s",
                        p.expectedMedia ? "🎵" : (heavyNonMedia ? "⚠️" : "•"),
                        p.appName, p.tag, DumpHelper.formatShortDurationExact(p.actualSeconds),
                        runningNow ? " [PRESO ATIVO!]" : ""));
            }
        }

        // 4. Maiores consumidores em tela apagada (CPU/Wakelock sem musica)
        StringBuilder offUidSb = new StringBuilder();
        int listedOff = 0;
        for (ScreenOffUidEntry u : offUids) {
            if (u.isSystemUid && u.nonAudioOffMah < 120.0) continue;
            if (!u.isSystemUid && u.nonAudioOffMah < 12.0 && u.audioOffMah < 15.0) continue;
            if (!u.isSystemUid && u.nonAudioOffMah >= 20.0) {
                addSuspect(context, suspectMap, u.packageName, u.appName,
                        String.format(Locale.US, "%.0f mAh apag. (CPU %.0f)", u.nonAudioOffMah, u.cpuMah),
                        100.0 + u.nonAudioOffMah, false);
            }
            if (!u.isSystemUid && u.nonAudioOffMah >= 45.0) {
                attention = true;
                if (u.nonAudioOffMah >= 90.0) critical = true;
                if (topCulprit.isEmpty()) topCulprit = u.appName;
            }
            if (listedOff < 4) {
                listedOff++;
                offUidSb.append(String.format(Locale.US, "\n   • %s: %.0f mAh apag. (CPU %.0f%s)",
                        u.appName, u.nonAudioOffMah, u.cpuMah,
                        u.wakelockSeconds >= 60 ? (", lock " + DumpHelper.formatShortDurationExact(u.wakelockSeconds)) : ""));
                if (u.audioOffMah >= 10.0) {
                    offUidSb.append(String.format(Locale.US, " + %.0f mAh áudio 🎵", u.audioOffMah));
                }
            }
        }

        for (SuspectApp s : suspectMap.values()) {
            if (userDozeWhitelist.contains(s.packageName)) {
                s.inDozeUserWhitelist = true;
                s.reason = s.reason + " · [Whitelist Doze]";
            }
            rep.suspects.add(s);
        }
        Collections.sort(rep.suspects, new Comparator<SuspectApp>() {
            @Override
            public int compare(SuspectApp a, SuspectApp b) {
                if (a.protectedApp != b.protectedApp) return a.protectedApp ? 1 : -1;
                return Double.compare(b.weightScore, a.weightScore);
            }
        });

        rep.severity = critical ? "CRITICO" : (attention ? "ATENCAO" : "OK");
        if (critical) {
            rep.headline = topCulprit.isEmpty()
                    ? "CRÍTICO: Dreno nocivo em repouso detectado!"
                    : "CRÍTICO · Suspeito: " + topCulprit;
        } else if (attention) {
            rep.headline = topCulprit.isEmpty()
                    ? "ATENÇÃO: Atividade anômala em 2º plano"
                    : "ATENÇÃO · Observar: " + topCulprit;
        } else {
            rep.headline = liveMode
                    ? "LIMPO AGORA: Nenhum ladrão segurando CPU neste instante."
                    : "CICLO LIMPO: Sem ladrão relevante em tela apagada.";
        }

        StringBuilder notif = new StringBuilder();
        if (!findings.isEmpty()) notif.append(findings.get(0));
        if (!topCulprit.isEmpty()) {
            if (notif.length() > 0) notif.append(" · ");
            notif.append("Vilão: ").append(topCulprit);
        }
        rep.notificationSummary = notif.toString().trim();

        StringBuilder det = new StringBuilder();
        det.append(rep.headline).append("\n");
        for (String f : findings) det.append(f).append("\n");

        det.append("\n🔒 LOCKS ATIVOS NO INSTANTE (power):");
        if (powerText == null || powerText.trim().isEmpty()) {
            det.append("\n   Indisponível (serviço power restrito)");
        } else if (activeSb.length() == 0) {
            det.append(activeLocks.isEmpty()
                    ? "\n   Nenhum wake lock segurando a CPU neste segundo."
                    : "\n   Nenhum lock longo ativo agora (" + activeLocks.size() + " curtos <3s).");
        } else {
            det.append(activeSb.toString());
        }

        det.append("\n\n⏱️ MAIORES LOCKS EFETIVOS DO CICLO (actual):");
        if (partialSb.length() == 0) {
            det.append("\n   Nenhum lock parcial > 3 min fora do normal.");
        } else {
            det.append(partialSb.toString());
        }

        det.append("\n\n🌙 TOP GASTO COM TELA APAGADA (sem áudio):");
        if (offUidSb.length() == 0) {
            det.append("\n   Nenhum app relevante drenando em tela apagada.");
        } else {
            det.append(offUidSb.toString());
        }

        if (suspectActiveCount == 0 && suspectPartialCount == 0 && !critical && !attention) {
            det.append("\n\n✅ Tudo sob controle: uso de repouso saudável.");
        }

        rep.details = det.toString().trim();
        return rep;
    }

    private static Set<String> parseUserDozeWhitelist(String idleText) {
        Set<String> set = new java.util.HashSet<String>();
        if (idleText == null || idleText.isEmpty()) return set;
        for (String line : idleText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("user,")) continue;
            String[] parts = trimmed.split(",");
            if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                set.add(parts[1].trim());
            }
        }
        return set;
    }

    private static List<ActiveLockEntry> parseActivePowerLocks(Context context, String powerText) {
        List<ActiveLockEntry> list = new ArrayList<ActiveLockEntry>();
        if (powerText == null || powerText.isEmpty()) return list;

        boolean inWakeLocks = false;
        Pattern lockPat = Pattern.compile("^\\s*([A-Z0-9_]+WAKE_LOCK)\\s+'([^']*)'\\s+ACQ=-([^\\s]+)(.*)$");
        Pattern chainPat = Pattern.compile("WorkChain\\{\\((\\d+)");
        Pattern wsPat = Pattern.compile("WorkSource\\{(\\d+)");
        Pattern uidPat = Pattern.compile("uid=(\\d+)");

        try {
            BufferedReader br = new BufferedReader(new StringReader(powerText));
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!inWakeLocks) {
                    if (trimmed.startsWith("Wake Locks:")) {
                        inWakeLocks = true;
                    }
                    continue;
                }
                if (trimmed.isEmpty() || trimmed.startsWith("Suspend Blockers:")) {
                    break;
                }
                Matcher m = lockPat.matcher(line);
                if (!m.find()) continue;

                ActiveLockEntry e = new ActiveLockEntry();
                e.lockType = m.group(1);
                String rawTag = m.group(2);
                String acqStr = m.group(3);
                String rest = m.group(4);

                e.ageSeconds = parseAcqSeconds(acqStr);
                e.isLong = rest.contains(" LONG ");

                String uidStr = "";
                Matcher mc = chainPat.matcher(rest);
                if (mc.find()) uidStr = mc.group(1);
                else {
                    Matcher mw = wsPat.matcher(rest);
                    if (mw.find()) uidStr = mw.group(1);
                    else {
                        Matcher mu = uidPat.matcher(rest);
                        if (mu.find()) uidStr = mu.group(1);
                    }
                }
                e.packageName = DumpHelper.resolveUidPrimaryPackage(context, uidStr);
                e.appName = resolveFriendlyApp(context, uidStr);
                e.tag = DumpHelper.cleanWakelockTag(rawTag);
                e.expectedMedia = isExpectedMediaTag(rawTag, e.appName);
                list.add(e);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static void parseBatteryStatsScreenOff(Context context, String bsText,
                                                   List<PartialLockStat> partialStats,
                                                   List<ScreenOffUidEntry> offUids) {
        if (bsText == null || bsText.isEmpty()) return;

        boolean inSinceLast = false;
        boolean inEstimated = false;
        boolean inPartial = false;
        String currentUid = "";
        String currentApp = "";

        Pattern partialPat = Pattern.compile("^Wake lock\\s+([^\\s]+)\\s+(.+?):\\s+(.+?)\\s*\\(");
        Pattern actualPat = Pattern.compile("actual=(\\d+)");
        Pattern runningPat = Pattern.compile("running for (\\d+)ms");
        Pattern uidHeaderPat = Pattern.compile("^UID\\s+([^:]+):");

        try {
            BufferedReader br = new BufferedReader(new StringReader(bsText));
            String line;
            while ((line = br.readLine()) != null) {
                if (!inSinceLast) {
                    if (line.startsWith("Statistics since last charge:")) {
                        inSinceLast = true;
                    }
                    continue;
                }

                String trimmed = line.trim();
                if (trimmed.startsWith("Estimated power use (mAh):")) {
                    inEstimated = true;
                    continue;
                }
                if (inEstimated) {
                    if (trimmed.startsWith("All ") || trimmed.startsWith("Per-app")) {
                        inEstimated = false;
                    } else {
                        Matcher mu = uidHeaderPat.matcher(trimmed);
                        if (mu.find()) {
                            currentUid = mu.group(1);
                            currentApp = resolveFriendlyApp(context, currentUid);
                            continue;
                        }
                        if (!currentUid.isEmpty() && trimmed.startsWith("(on battery, screen off/doze)")) {
                            ScreenOffUidEntry entry = new ScreenOffUidEntry();
                            entry.packageName = DumpHelper.resolveUidPrimaryPackage(context, currentUid);
                            entry.appName = currentApp;
                            entry.isSystemUid = "1000".equals(currentUid) || "0".equals(currentUid) || "1001".equals(currentUid);
                            entry.cpuMah = extractMetric(trimmed, "cpu=");
                            entry.audioOffMah = extractMetric(trimmed, "audio=");
                            double video = extractMetric(trimmed, "video=");
                            double camera = extractMetric(trimmed, "camera=");
                            double sensors = extractMetric(trimmed, "sensors=");
                            double gnss = extractMetric(trimmed, "gnss=");
                            double wifi = extractMetric(trimmed, "wifi=");
                            double bt = extractMetric(trimmed, "bluetooth=");
                            double wlMah = extractMetric(trimmed, "wakelock=");
                            entry.nonAudioOffMah = entry.cpuMah + video + camera + sensors + gnss + wifi + bt + wlMah;
                            int wlIdx = trimmed.indexOf("wakelock=");
                            if (wlIdx >= 0) {
                                int pOpen = trimmed.indexOf('(', wlIdx);
                                int pClose = trimmed.indexOf(')', wlIdx);
                                if (pOpen > 0 && pClose > pOpen) {
                                    entry.wakelockSeconds = DumpHelper.parseDurationSeconds(trimmed.substring(pOpen + 1, pClose));
                                }
                            }
                            offUids.add(entry);
                            continue;
                        }
                    }
                }

                if (trimmed.equals("All partial wake locks:")) {
                    inPartial = true;
                    continue;
                }
                if (inPartial) {
                    if (trimmed.startsWith("Wake lock ")) {
                        Matcher mp = partialPat.matcher(trimmed);
                        if (mp.find()) {
                            PartialLockStat stat = new PartialLockStat();
                            String uid = mp.group(1);
                            String rawTag = mp.group(2);
                            stat.packageName = DumpHelper.resolveUidPrimaryPackage(context, uid);
                            stat.appName = resolveFriendlyApp(context, uid);
                            stat.tag = DumpHelper.cleanWakelockTag(rawTag);

                            Matcher ma = actualPat.matcher(trimmed);
                            if (ma.find()) {
                                stat.actualSeconds = Double.parseDouble(ma.group(1)) / 1000.0;
                            } else {
                                stat.actualSeconds = DumpHelper.parseDurationSeconds(mp.group(3));
                            }
                            Matcher mr = runningPat.matcher(trimmed);
                            if (mr.find()) {
                                stat.runningSeconds = Double.parseDouble(mr.group(1)) / 1000.0;
                            }
                            stat.expectedMedia = isExpectedMediaTag(rawTag, stat.appName);
                            stat.knownWatcher = isSuspiciousWatcherTag(rawTag);
                            if (stat.actualSeconds >= 120.0 || stat.runningSeconds >= 2.0) {
                                partialStats.add(stat);
                            }
                        }
                    } else if (!trimmed.isEmpty()) {
                        inPartial = false;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean isExpectedMediaTag(String rawTag, String appName) {
        String lowTag = rawTag != null ? rawTag.toLowerCase(Locale.US) : "";
        String lowApp = appName != null ? appName.toLowerCase(Locale.US) : "";
        return lowTag.contains("audiomix") || lowTag.contains("exoplayer") || lowTag.contains("mediaplayer")
                || lowApp.contains("spotify") || lowApp.contains("poweramp") || lowApp.contains("youtube");
    }

    private static boolean isSuspiciousWatcherTag(String rawTag) {
        String low = rawTag != null ? rawTag.toLowerCase(Locale.US) : "";
        return low.contains("hotword") || low.contains("sigcollector") || low.contains("unifiedremotesync")
                || low.contains("wakelockholder") || low.contains("tripper");
    }

    private static double extractMetric(String line, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "([0-9.]+)").matcher(line);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    private static double parseAcqSeconds(String acq) {
        double secs = DumpHelper.parseDurationSeconds(acq);
        Matcher ms = Pattern.compile("(\\d+)ms").matcher(acq);
        if (ms.find()) {
            try { secs += Double.parseDouble(ms.group(1)) / 1000.0; } catch (NumberFormatException ignored) {}
        }
        return secs;
    }

    private static void addSuspect(Context context, Map<String, SuspectApp> map,
                                   String pkg, String appName, String reason,
                                   double score, boolean expectedMedia) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        String cleanPkg = pkg.trim();
        SuspectApp existing = map.get(cleanPkg);
        if (existing != null) {
            if (score > existing.weightScore) {
                existing.weightScore = score;
                existing.reason = reason;
            }
            if (expectedMedia) existing.expectedMedia = true;
            return;
        }
        SuspectApp s = new SuspectApp();
        s.packageName = cleanPkg;
        s.appName = (appName != null && !appName.isEmpty()) ? appName : cleanPkg;
        s.reason = reason;
        s.weightScore = score;
        s.expectedMedia = expectedMedia;
        s.protectedApp = expectedMedia || SystemTweaksController.isProtectedPackage(context, cleanPkg);
        map.put(cleanPkg, s);
    }

    private static String resolveFriendlyApp(Context context, String uidStr) {
        if ("1000".equals(uidStr)) return "Sistema (1000)";
        if ("1001".equals(uidStr)) return "Rádio (1001)";
        if ("0".equals(uidStr)) return "Kernel/Root (0)";
        if ("1041".equals(uidStr)) return "AudioServer (1041)";
        if ("10160".equals(uidStr)) return "Samsung Agent Storage";
        return DumpHelper.resolveUidName(context, uidStr);
    }

    private static String readFileIfExists(File file) {
        if (file == null || !file.exists()) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
        return sb.toString();
    }
}

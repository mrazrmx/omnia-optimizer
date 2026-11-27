package com.omnia.optimizer;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analisador de consumo real de RAM por processo/app inspirado no MemHogs (0% em segundo plano).
 * Agrega renderer isolado (WebView/Chrome) ao app hospedeiro via dumpsys activity lru + PackageManager.
 */
public final class MemHogsController {

    public static final double HOT_GROUP_RAM_SHARE = 0.15d;
    public static final float HOT_DEVICE_USED_FRACTION = 0.85f;

    private static final int PER_USER_UID_RANGE = 100000;
    private static final int FIRST_APP_UID = 10000;

    private static final Pattern PROCESS_LINE = Pattern.compile("([\\d,]+)K:\\s+(.+?)\\s+\\(pid\\s+(\\d+)[^)]*\\)");
    private static final Pattern KB_VALUE = Pattern.compile("([\\d,]+)K");
    private static final Pattern ISOLATED_PROCESS = Pattern.compile("\\s(\\d+):\\S+/u(\\d+)a(\\d+)i\\d+");
    private static final Pattern VALID_PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    public static final class MemMember {
        public final String processName;
        public final int pid;
        public final long pssKb;

        public MemMember(String processName, int pid, long pssKb) {
            this.processName = processName;
            this.pid = pid;
            this.pssKb = pssKb;
        }
    }

    public static final class MemGroup {
        public final String owner;
        public final String label;
        public final boolean isApp;
        public final long pssKb;
        public final double shareOfRam;
        public final boolean isHot;
        public final boolean canReclaim;
        public final boolean canForceStop;
        public final boolean protectedApp;
        public final List<MemMember> members;

        public MemGroup(String owner, String label, boolean isApp, long pssKb, double shareOfRam,
                        boolean canReclaim, boolean canForceStop, boolean protectedApp, List<MemMember> members) {
            this.owner = owner;
            this.label = label;
            this.isApp = isApp;
            this.pssKb = pssKb;
            this.shareOfRam = shareOfRam;
            this.isHot = shareOfRam >= HOT_GROUP_RAM_SHARE;
            this.canReclaim = canReclaim;
            this.canForceStop = canForceStop;
            this.protectedApp = protectedApp;
            this.members = members;
        }
    }

    public static final class MemGauge {
        public final long totalBytes;
        public final long availBytes;
        public final long usedBytes;
        public final boolean lowMemory;
        public final float usedFraction;

        public MemGauge(long totalBytes, long availBytes, boolean lowMemory) {
            this.totalBytes = Math.max(0L, totalBytes);
            this.availBytes = Math.max(0L, availBytes);
            this.usedBytes = Math.max(0L, this.totalBytes - this.availBytes);
            this.lowMemory = lowMemory;
            this.usedFraction = this.totalBytes > 0 ? (float) ((double) this.usedBytes / (double) this.totalBytes) : 0f;
        }
    }

    public static final class MemSnapshot {
        public final long totalRamKb;
        public final long freeRamKb;
        public final long usedRamKb;
        public final float usedFraction;
        public final int processCount;
        public final String timestampText;
        public final List<MemGroup> groups;
        public final MemGauge gauge;
        public final String error;

        public MemSnapshot(long totalRamKb, long freeRamKb, long usedRamKb, int processCount,
                           String timestampText, List<MemGroup> groups, MemGauge gauge, String error) {
            this.totalRamKb = totalRamKb;
            this.freeRamKb = freeRamKb;
            this.usedRamKb = usedRamKb;
            this.usedFraction = totalRamKb > 0 ? (float) ((double) usedRamKb / (double) totalRamKb) : gauge.usedFraction;
            this.processCount = processCount;
            this.timestampText = timestampText;
            this.groups = groups;
            this.gauge = gauge;
            this.error = error;
        }
    }

    public static final class ReclaimOutcome {
        public final boolean ok;
        public final String message;
        public final MemSnapshot afterSnapshot;

        public ReclaimOutcome(boolean ok, String message, MemSnapshot afterSnapshot) {
            this.ok = ok;
            this.message = message;
            this.afterSnapshot = afterSnapshot;
        }
    }

    private static final class ProcessSample {
        final String name;
        final int pid;
        final long pssKb;

        ProcessSample(String name, int pid, long pssKb) {
            this.name = name;
            this.pid = pid;
            this.pssKb = pssKb;
        }
    }

    private MemHogsController() {
    }

    public static MemGauge readDeviceGauge(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return new MemGauge(mi.totalMem, mi.availMem, mi.lowMemory);
            }
        } catch (Throwable ignored) {
        }
        return new MemGauge(0L, 0L, false);
    }

    public static MemSnapshot captureSnapshot(Context context) {
        MemGauge gauge = readDeviceGauge(context);
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        if (!ShizukuBridge.ensureAttached(context) || !ShizukuBridge.hasPermission(context)) {
            return new MemSnapshot(0L, 0L, 0L, 0, ts, Collections.<MemGroup>emptyList(), gauge,
                    "Ative e autorize o Shizuku na aba Ajustes para listar processos com precisão (dumpsys meminfo).");
        }

        String cmd = "dumpsys meminfo; echo '===OMNIA_LRU==='; dumpsys activity lru";
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, cmd, 25000L);
        String raw = res.stdout != null ? res.stdout : "";
        if (raw.isEmpty()) {
            String err = (res.error != null && !res.error.isEmpty()) ? res.error : res.stderr;
            if (err == null || err.isEmpty()) err = "Sem saída do dumpsys meminfo.";
            return new MemSnapshot(0L, 0L, 0L, 0, ts, Collections.<MemGroup>emptyList(), gauge, err);
        }

        String meminfoText = raw;
        String lruText = "";
        int markerIdx = raw.indexOf("===OMNIA_LRU===");
        if (markerIdx >= 0) {
            meminfoText = raw.substring(0, markerIdx);
            lruText = raw.substring(markerIdx + "===OMNIA_LRU===".length());
        }

        return parseAndGroup(context, meminfoText, lruText, gauge, ts);
    }

    public static ReclaimOutcome reclaimPackage(Context context, MemGroup target, boolean forceStop) {
        if (target == null || !isValidPackageName(target.owner)) {
            return new ReclaimOutcome(false, "Pacote inválido ou processo de kernel não gerenciável.", null);
        }
        if (context.getPackageName().equals(target.owner)) {
            return new ReclaimOutcome(false, "O próprio Omnia Optimizer é protegido.", null);
        }
        if (forceStop && !target.canForceStop) {
            return new ReclaimOutcome(false, "Force-Stop bloqueado para app persistente/protegido.", null);
        }
        if (!ShizukuBridge.ensureAttached(context) || !ShizukuBridge.hasPermission(context)) {
            return new ReclaimOutcome(false, "Shizuku não autorizado.", null);
        }

        String cmd = (forceStop ? "am force-stop " : "am kill ") + target.owner;
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, cmd, 10000L);
        if (!res.isOk()) {
            String err = (res.stderr != null && !res.stderr.isEmpty()) ? res.stderr : "exit=" + res.exitCode;
            return new ReclaimOutcome(false, "Falha em " + cmd + ": " + err, null);
        }

        try {
            Thread.sleep(650L);
        } catch (InterruptedException ignored) {
        }

        MemSnapshot after = captureSnapshot(context);
        long afterKb = 0L;
        for (MemGroup g : after.groups) {
            if (target.owner.equals(g.owner)) {
                afterKb = g.pssKb;
                break;
            }
        }
        long freedKb = target.pssKb - afterKb;
        String modeLabel = forceStop ? "Force-Stop" : "Kill 2º plano";
        String msg;
        if (freedKb > 0L) {
            msg = "Liberados " + formatKb(freedKb) + " de " + target.label + " (" + modeLabel + ")";
        } else if (forceStop) {
            msg = target.label + " encerrado (" + modeLabel + "); sem saldo extra em PSS.";
        } else {
            msg = target.label + ": sem processos em cache liberáveis por Kill BG (use Stop se precisar).";
        }
        return new ReclaimOutcome(true, msg, after);
    }

    private static MemSnapshot parseAndGroup(Context context, String meminfoText, String lruText, MemGauge gauge, String ts) {
        String[] rawLines = meminfoText.split("\\r?\\n");
        long totalRamKb = 0L;
        long freeRamKb = 0L;
        long usedRamKb = 0L;
        boolean inProcessSection = false;
        List<ProcessSample> samples = new ArrayList<ProcessSample>();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.startsWith("Total RAM:") && totalRamKb == 0L) {
                totalRamKb = extractFirstKb(line);
            } else if (line.startsWith("Free RAM:") && freeRamKb == 0L) {
                freeRamKb = extractFirstKb(line);
            } else if (line.startsWith("Used RAM:") && usedRamKb == 0L) {
                usedRamKb = extractFirstKb(line);
            }

            if (!inProcessSection) {
                if ("Total PSS by process:".equals(line)) {
                    inProcessSection = true;
                }
                continue;
            }

            if (line.isEmpty() || line.startsWith("Total PSS by")) {
                break;
            }

            Matcher pm = PROCESS_LINE.matcher(line);
            if (pm.matches()) {
                long pss = parseKbSafe(pm.group(1));
                String name = pm.group(2).trim();
                int pid = parseIntSafe(pm.group(3), -1);
                if (pid > 0) {
                    samples.add(new ProcessSample(name, pid, pss));
                }
            }
        }

        if (samples.isEmpty()) {
            return new MemSnapshot(totalRamKb, freeRamKb, usedRamKb, 0, ts, Collections.<MemGroup>emptyList(), gauge,
                    "Não foi possível localizar a seção 'Total PSS by process:' no dumpsys meminfo.");
        }

        if (totalRamKb <= 0L && gauge.totalBytes > 0L) {
            totalRamKb = gauge.totalBytes / 1024L;
        }
        if (usedRamKb <= 0L && totalRamKb > 0L && freeRamKb > 0L) {
            usedRamKb = Math.max(0L, totalRamKb - freeRamKb);
        }

        PackageManager pm = context.getPackageManager();
        Map<Integer, String> isolatedHosts = buildIsolatedHostMap(pm, lruText);
        Map<String, ApplicationInfo> appInfoCache = new HashMap<String, ApplicationInfo>();
        Map<String, String> appLabelCache = new HashMap<String, String>();

        Map<String, List<ProcessSample>> byOwner = new LinkedHashMap<String, List<ProcessSample>>();
        for (ProcessSample s : samples) {
            String owner = isolatedHosts.get(s.pid);
            if (owner == null) {
                owner = resolveOwner(pm, s.name, appInfoCache);
            }
            List<ProcessSample> bucket = byOwner.get(owner);
            if (bucket == null) {
                bucket = new ArrayList<ProcessSample>();
                byOwner.put(owner, bucket);
            }
            bucket.add(s);
        }

        String ownPkg = context.getPackageName();
        List<MemGroup> groups = new ArrayList<MemGroup>(byOwner.size());
        for (Map.Entry<String, List<ProcessSample>> entry : byOwner.entrySet()) {
            String owner = entry.getKey();
            List<ProcessSample> list = entry.getValue();
            Collections.sort(list, new Comparator<ProcessSample>() {
                @Override
                public int compare(ProcessSample a, ProcessSample b) {
                    return Long.compare(b.pssKb, a.pssKb);
                }
            });

            long sumKb = 0L;
            List<MemMember> members = new ArrayList<MemMember>(list.size());
            for (ProcessSample ps : list) {
                sumKb += ps.pssKb;
                members.add(new MemMember(ps.name, ps.pid, ps.pssKb));
            }

            ApplicationInfo ai = getAppInfoCached(pm, owner, appInfoCache);
            boolean isApp = ai != null;
            String label = getAppLabelCached(pm, owner, ai, appLabelCache);
            double share = totalRamKb > 0L ? ((double) sumKb / (double) totalRamKb) : 0.0d;
            boolean canReclaim = isApp && !ownPkg.equals(owner);
            boolean persistent = ai != null && (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
            boolean protectedPkg = !isApp || ownPkg.equals(owner) || SystemTweaksController.isProtectedPackage(context, owner);
            boolean canForceStop = canReclaim && !persistent && !protectedPkg;

            groups.add(new MemGroup(owner, label, isApp, sumKb, share, canReclaim, canForceStop, protectedPkg, members));
        }

        Collections.sort(groups, new Comparator<MemGroup>() {
            @Override
            public int compare(MemGroup a, MemGroup b) {
                return Long.compare(b.pssKb, a.pssKb);
            }
        });

        return new MemSnapshot(totalRamKb, freeRamKb, usedRamKb, samples.size(), ts, groups, gauge, null);
    }

    private static Map<Integer, String> buildIsolatedHostMap(PackageManager pm, String lruText) {
        Map<Integer, String> hostMap = new HashMap<Integer, String>();
        if (lruText == null || lruText.isEmpty()) return hostMap;

        Matcher m = ISOLATED_PROCESS.matcher(lruText);
        while (m.find()) {
            int pid = parseIntSafe(m.group(1), -1);
            int userId = parseIntSafe(m.group(2), -1);
            int appId = parseIntSafe(m.group(3), -1);
            if (pid > 0 && userId >= 0 && appId >= 0) {
                int hostUid = userId * PER_USER_UID_RANGE + FIRST_APP_UID + appId;
                try {
                    String[] pkgs = pm.getPackagesForUid(hostUid);
                    if (pkgs != null && pkgs.length > 0 && pkgs[0] != null) {
                        hostMap.put(pid, pkgs[0]);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return hostMap;
    }

    private static String resolveOwner(PackageManager pm, String processName, Map<String, ApplicationInfo> cache) {
        int colon = processName.indexOf(':');
        String base = colon >= 0 ? processName.substring(0, colon) : processName;
        String candidate = base;
        while (!candidate.isEmpty()) {
            if (getAppInfoCached(pm, candidate, cache) != null) {
                return candidate;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot <= 0) break;
            candidate = candidate.substring(0, dot);
            if (candidate.indexOf('.') < 0) break;
        }
        return base;
    }

    private static ApplicationInfo getAppInfoCached(PackageManager pm, String pkg, Map<String, ApplicationInfo> cache) {
        if (cache.containsKey(pkg)) {
            return cache.get(pkg);
        }
        ApplicationInfo ai = null;
        if (isValidPackageName(pkg)) {
            try {
                ai = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Throwable ignored) {
            }
        }
        cache.put(pkg, ai);
        return ai;
    }

    private static String getAppLabelCached(PackageManager pm, String owner, ApplicationInfo ai, Map<String, String> cache) {
        if (cache.containsKey(owner)) return cache.get(owner);
        String label = owner;
        if (ai != null) {
            try {
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) label = cs.toString();
            } catch (Throwable ignored) {
            }
        }
        cache.put(owner, label);
        return label;
    }

    public static boolean isValidPackageName(String name) {
        return name != null && VALID_PACKAGE_NAME.matcher(name).matches();
    }

    public static String formatKb(long kb) {
        if (kb < 0L) kb = 0L;
        if (kb >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GiB", kb / (1024.0 * 1024.0));
        }
        if (kb >= 1024L) {
            return String.format(Locale.US, "%.1f MiB", kb / 1024.0);
        }
        return kb + " KiB";
    }

    public static String formatBytes(long bytes) {
        return formatKb(Math.max(0L, bytes) / 1024L);
    }

    private static long extractFirstKb(String line) {
        Matcher m = KB_VALUE.matcher(line);
        if (m.find()) {
            return parseKbSafe(m.group(1));
        }
        return 0L;
    }

    private static long parseKbSafe(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int parseIntSafe(String raw, int def) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}

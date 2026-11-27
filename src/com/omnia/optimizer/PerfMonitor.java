package com.omnia.optimizer;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
 * Leitor universal sob demanda de hardware (descobre dinamicamente nucleos, clusters, GPU, barramento DDR e termica).
 */
public final class PerfMonitor {

    public static final class PerfSnapshot {
        public final String timestampText;
        public final String cpuText;
        public final String thermalsText;
        public final String memoryText;
        public final String gpuPowerText;

        public PerfSnapshot(String timestampText, String cpuText, String thermalsText,
                            String memoryText, String gpuPowerText) {
            this.timestampText = timestampText;
            this.cpuText = cpuText;
            this.thermalsText = thermalsText;
            this.memoryText = memoryText;
            this.gpuPowerText = gpuPowerText;
        }
    }

    private static final class CpuPolicyInfo {
        String policyName;
        String policyPath;
        String shortLabel;
        String roleTag;
        long hwMaxKhz;
        int firstCpu = -1;
    }

    private static final Pattern THERMAL_LINE_PATTERN = Pattern.compile(
            "Temperature\\{mValue=([^,}]+),\\s*mType=([^,}]+),\\s*mName=([^,}]+),\\s*mStatus=([^}]+)\\}"
    );

    private static boolean topologyScanned = false;
    private static final List<CpuPolicyInfo> policies = new ArrayList<CpuPolicyInfo>();
    private static final Map<Integer, CpuPolicyInfo> cpuToPolicy = new HashMap<Integer, CpuPolicyInfo>();
    private static int detectedCpuCount = 0;

    private static boolean thermalZonesScanned = false;
    private static boolean thermalZonesReadable = false;
    private static String ddrThermalPath = null;
    private static String batThermalPath = null;
    private static String gpuThermalPath = null;
    private static final List<String> cpuThermalPaths = new ArrayList<String>();

    private static String discoveredDdrDevfreqCur = null;
    private static boolean ddrDevfreqScanned = false;

    private PerfMonitor() {
    }

    public static PerfSnapshot readSnapshot(Context context) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String cpu = readCpuSection();
        String thermals = readThermalsSection(context);
        String memory = readMemorySection(context);
        String gpuPower = readGpuAndPowerSection(context);
        return new PerfSnapshot(ts, cpu, thermals, memory, gpuPower);
    }

    public static synchronized void ensureTopologyScanned() {
        if (topologyScanned) return;
        topologyScanned = true;

        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] rootFiles = cpuRoot.listFiles();
        int maxCpuIdx = -1;
        if (rootFiles != null) {
            for (File f : rootFiles) {
                String name = f.getName();
                if (name.matches("cpu\\d+")) {
                    try {
                        int idx = Integer.parseInt(name.substring(3));
                        if (idx > maxCpuIdx) maxCpuIdx = idx;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        detectedCpuCount = maxCpuIdx >= 0 ? (maxCpuIdx + 1) : Math.max(1, Runtime.getRuntime().availableProcessors());

        File cpufreqDir = new File("/sys/devices/system/cpu/cpufreq");
        File[] policyDirs = cpufreqDir.listFiles();
        if (policyDirs != null) {
            for (File pDir : policyDirs) {
                String pName = pDir.getName();
                if (!pDir.isDirectory() || !pName.startsWith("policy")) continue;
                CpuPolicyInfo info = new CpuPolicyInfo();
                info.policyName = pName;
                info.policyPath = pDir.getAbsolutePath();
                Long hwMax = readLong(new File(pDir, "cpuinfo_max_freq").getAbsolutePath());
                Long curMax = readLong(new File(pDir, "scaling_max_freq").getAbsolutePath());
                info.hwMaxKhz = hwMax != null ? hwMax : (curMax != null ? curMax : 0L);

                String cpus = readFirstLine(new File(pDir, "related_cpus").getAbsolutePath());
                if (cpus == null || cpus.isEmpty()) {
                    cpus = readFirstLine(new File(pDir, "affected_cpus").getAbsolutePath());
                }
                if (cpus != null && !cpus.isEmpty()) {
                    String[] parts = cpus.trim().split("\\s+");
                    for (String tok : parts) {
                        try {
                            int cpuIdx = Integer.parseInt(tok);
                            if (info.firstCpu < 0 || cpuIdx < info.firstCpu) info.firstCpu = cpuIdx;
                            cpuToPolicy.put(cpuIdx, info);
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    try {
                        int startIdx = Integer.parseInt(pName.replace("policy", ""));
                        info.firstCpu = startIdx;
                        cpuToPolicy.put(startIdx, info);
                    } catch (NumberFormatException ignored) {}
                }
                policies.add(info);
            }
        }

        Collections.sort(policies, new Comparator<CpuPolicyInfo>() {
            @Override
            public int compare(CpuPolicyInfo a, CpuPolicyInfo b) {
                int c = Long.compare(a.hwMaxKhz, b.hwMaxKhz);
                if (c != 0) return c;
                return Integer.compare(a.firstCpu, b.firstCpu);
            }
        });

        int count = policies.size();
        for (int i = 0; i < count; i++) {
            CpuPolicyInfo info = policies.get(i);
            if (count == 1) {
                info.shortLabel = "C0";
                info.roleTag = "[MAIN  ]";
            } else if (i == 0) {
                info.shortLabel = "L";
                info.roleTag = "[LITTLE]";
            } else if (i == count - 1) {
                info.shortLabel = "P";
                info.roleTag = "[PRIME ]";
            } else if (count == 3) {
                info.shortLabel = "G";
                info.roleTag = "[GOLD  ]";
            } else {
                info.shortLabel = "M" + i;
                info.roleTag = "[MID" + i + "  ]";
            }
        }
    }

    public static String getQuickClusterSummary() {
        ensureTopologyScanned();
        if (policies.isEmpty()) {
            return "CPU: " + detectedCpuCount + " Núcleos";
        }
        CpuPolicyInfo low = policies.get(0);
        CpuPolicyInfo high = policies.get(policies.size() - 1);
        String lowGhz = readFreqGhz("/sys/devices/system/cpu/cpu" + Math.max(0, low.firstCpu) + "/cpufreq/scaling_cur_freq");
        String highGhz = readFreqGhz("/sys/devices/system/cpu/cpu" + Math.max(0, high.firstCpu) + "/cpufreq/scaling_cur_freq");
        if (highGhz != null && lowGhz != null) {
            return String.format(Locale.US, "CPU (%dN): %s | %s", detectedCpuCount, highGhz, lowGhz);
        }
        return "CPU: " + detectedCpuCount + " Núcleos";
    }

    private static String readCpuSection() {
        ensureTopologyScanned();
        StringBuilder sb = new StringBuilder();
        int readableCores = 0;

        if (!policies.isEmpty()) {
            sb.append("Tetos ativos (Atual / HW): ");
            for (int i = 0; i < policies.size(); i++) {
                CpuPolicyInfo p = policies.get(i);
                if (i > 0) sb.append(" | ");
                String act = readFreqMhz(p.policyPath + "/scaling_max_freq");
                String hw = p.hwMaxKhz > 0 ? String.valueOf(p.hwMaxKhz / 1000L) : "?";
                sb.append(p.shortLabel).append(" ").append(act != null ? act : "?").append("/").append(hw);
            }
            sb.append(" MHz\n");

            CpuPolicyInfo top = policies.get(policies.size() - 1);
            String governor = readFirstLine(top.policyPath + "/scaling_governor");
            if (governor == null) governor = readFirstLine(policies.get(0).policyPath + "/scaling_governor");
            sb.append("Governor: ").append(governor != null ? governor : "Restrito").append(" · ").append(detectedCpuCount).append(" núcleos\n\n");
        } else {
            sb.append("Núcleos detectados: ").append(detectedCpuCount).append("\n\n");
        }

        for (int i = 0; i < detectedCpuCount; i++) {
            CpuPolicyInfo mapped = cpuToPolicy.get(i);
            String role = mapped != null ? mapped.roleTag : "[CORE  ]";
            String prefix = (i < 10 ? "C" + i : "C" + i) + " " + role;

            String onlineStr = readFirstLine("/sys/devices/system/cpu/cpu" + i + "/online");
            boolean online = (i == 0) || onlineStr == null || "1".equals(onlineStr);
            if (!online) {
                sb.append(String.format(Locale.US, "%s OFFLINE         [--------]\n", prefix));
                continue;
            }

            Long curKhz = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            Long maxKhz = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
            if (curKhz != null && maxKhz != null && maxKhz > 0) {
                readableCores++;
                long curMhz = curKhz / 1000L;
                long maxMhz = maxKhz / 1000L;
                double ratio = Math.max(0.0, Math.min(1.0, (double) curKhz / (double) maxKhz));
                sb.append(String.format(Locale.US, "%s %4d / %4d MHz %s\n", prefix, curMhz, maxMhz, buildAsciiBar(ratio, 8)));
            } else {
                sb.append(String.format(Locale.US, "%s Restrito (SELinux)\n", prefix));
            }
        }

        if (readableCores == 0) {
            sb.append("\nNota: SELinux deste aparelho restringe leitura direta de cpufreq.");
        }
        return sb.toString().trim();
    }

    private static String readThermalsSection(Context context) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> halTemps = readThermalServiceTemps();
        scanThermalZonesOnce();

        String apTemp = halTemps.get("AP");
        String batHalTemp = halTemps.get("BAT");
        String paTemp = halTemps.get("PA");
        String skinTemp = halTemps.get("SKIN");
        String usbTemp = halTemps.get("USB");

        Double cpuSysfsMax = readMaxThermalCelsius(cpuThermalPaths);
        Double ddrSysfs = ddrThermalPath != null ? readThermalCelsius(ddrThermalPath) : null;
        Double gpuSysfs = gpuThermalPath != null ? readThermalCelsius(gpuThermalPath) : null;
        Double batSysfs = batThermalPath != null ? readThermalCelsius(batThermalPath) : null;
        Float batStickyTemp = readBatteryTempSticky(context);

        sb.append("Processador (CPU/AP) : ");
        if (cpuSysfsMax != null && apTemp != null) {
            sb.append(String.format(Locale.US, "%.1f C (SoC %s C)\n", cpuSysfsMax, apTemp));
        } else if (apTemp != null) {
            sb.append(apTemp).append(" C (ThermalHAL AP)\n");
        } else if (cpuSysfsMax != null) {
            sb.append(String.format(Locale.US, "%.1f C (sysfs CPU)\n", cpuSysfsMax));
        } else {
            sb.append("Indisponivel (DUMP/SELinux)\n");
        }

        sb.append("Memoria (DDR)        : ");
        if (ddrSysfs != null) {
            sb.append(String.format(Locale.US, "%.1f C (sensor ddr)\n", ddrSysfs));
        } else if (!thermalZonesReadable) {
            sb.append("Restrito por SELinux neste aparelho\n");
        } else {
            sb.append("Sensor 'ddr' ausente neste SoC\n");
        }

        sb.append("Bateria              : ");
        if (batStickyTemp != null && batHalTemp != null) {
            sb.append(String.format(Locale.US, "%.1f C (HAL %s C)\n", batStickyTemp, batHalTemp));
        } else if (batStickyTemp != null) {
            sb.append(String.format(Locale.US, "%.1f C (BatteryManager)\n", batStickyTemp));
        } else if (batSysfs != null) {
            sb.append(String.format(Locale.US, "%.1f C (sysfs)\n", batSysfs));
        } else if (batHalTemp != null) {
            sb.append(batHalTemp).append(" C (HAL BAT)\n");
        } else {
            sb.append("Indisponivel\n");
        }

        sb.append("GPU                  : ");
        if (gpuSysfs != null) {
            sb.append(String.format(Locale.US, "%.1f C (sysfs)\n", gpuSysfs));
        } else {
            sb.append(thermalZonesReadable ? "Ausente no sysfs (via AP)\n" : "Restrito por SELinux (via AP)\n");
        }

        sb.append("Carcaca (SKIN) / PA  : ");
        sb.append(skinTemp != null ? skinTemp + " C" : "--");
        sb.append(" / ");
        sb.append(paTemp != null ? paTemp + " C" : "--").append("\n");

        sb.append("Porta USB-C          : ");
        sb.append(usbTemp != null ? usbTemp + " C" : "--");

        return sb.toString().trim();
    }

    private static String readMemorySection(Context context) {
        StringBuilder sb = new StringBuilder();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
        }

        Map<String, Long> meminfoKb = readProcMemInfoKb();
        Long totalKb = meminfoKb.get("MemTotal");
        Long availKb = meminfoKb.get("MemAvailable");
        Long freeKb = meminfoKb.get("MemFree");
        Long cachedKb = meminfoKb.get("Cached");
        Long buffersKb = meminfoKb.get("Buffers");
        Long swapTotalKb = meminfoKb.get("SwapTotal");
        Long swapFreeKb = meminfoKb.get("SwapFree");

        if (totalKb != null && availKb != null) {
            long usedKb = Math.max(0L, totalKb - availKb);
            int usedPct = totalKb > 0 ? (int) Math.round((usedKb * 100.0) / totalKb) : 0;
            long cacheAndBufKb = (cachedKb != null ? cachedKb : 0L) + (buffersKb != null ? buffersKb : 0L);

            sb.append(String.format(Locale.US, "RAM Total (Linux) : %.2f GiB\n", kbToGib(totalKb)));
            sb.append(String.format(Locale.US, "Em Uso (Real)     : %.2f GiB (%d%%)\n", kbToGib(usedKb), usedPct));
            sb.append(String.format(Locale.US, "Disponivel App    : %.2f GiB\n", kbToGib(availKb)));
            if (freeKb != null) {
                sb.append(String.format(Locale.US, "Livre Bruta       : %d MiB\n", freeKb / 1024L));
            }
            sb.append(String.format(Locale.US, "Cache + Buffers   : %.2f GiB\n", kbToGib(cacheAndBufKb)));
            if (swapTotalKb != null && swapFreeKb != null && swapTotalKb > 0) {
                long swapUsedKb = Math.max(0L, swapTotalKb - swapFreeKb);
                sb.append(String.format(Locale.US, "Swap ZRAM (Kernel): %.2f / %.2f GiB\n",
                        kbToGib(swapUsedKb), kbToGib(swapTotalKb)));
            }
        } else if (mi.totalMem > 0) {
            long usedBytes = Math.max(0L, mi.totalMem - mi.availMem);
            int usedPct = (int) Math.round((usedBytes * 100.0) / mi.totalMem);
            sb.append(String.format(Locale.US, "RAM Total         : %.2f GiB\n", bytesToGib(mi.totalMem)));
            sb.append(String.format(Locale.US, "Em Uso            : %.2f GiB (%d%%)\n", bytesToGib(usedBytes), usedPct));
            sb.append(String.format(Locale.US, "Disponivel        : %.2f GiB\n", bytesToGib(mi.availMem)));
        } else {
            sb.append("RAM               : Indisponivel\n");
        }

        String ddrCur = readFreqMhz("/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq");
        String ddrMax = readFreqMhz("/sys/devices/system/cpu/bus_dcvs/DDR/hw_max_freq");
        if (ddrCur != null) {
            sb.append("\nClock DDR / Memória: ").append(ddrCur);
            if (ddrMax != null) sb.append(" / ").append(ddrMax);
            sb.append(" MHz");
            String llccCur = readFreqMhz("/sys/devices/system/cpu/bus_dcvs/LLCC/cur_freq");
            String l3Cur = readFreqMhz("/sys/devices/system/cpu/bus_dcvs/L3/cur_freq");
            if (l3Cur != null || llccCur != null) {
                sb.append("\nCache L3 / LLCC    : ")
                  .append(l3Cur != null ? l3Cur + " MHz" : "--")
                  .append(" / ")
                  .append(llccCur != null ? llccCur + " MHz" : "--");
            }
        } else {
            String altDdrPath = discoverDdrDevfreqPath();
            String altMhz = altDdrPath != null ? readAnyFreqMhz(altDdrPath) : null;
            sb.append("\nClock DDR / Memória: ");
            sb.append(altMhz != null ? altMhz + " MHz" : "Nao exposto neste SoC");
        }

        return sb.toString().trim();
    }

    private static String readGpuAndPowerSection(Context context) {
        StringBuilder sb = new StringBuilder();

        String gpuClock = readFirstLine("/sys/class/kgsl/kgsl-3d0/clock_mhz");
        String gpuBusy = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        String gpuModel = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (gpuClock != null) {
            sb.append(gpuModel != null && !gpuModel.isEmpty() ? "GPU " + gpuModel : "GPU Adreno").append(": ");
            sb.append(gpuClock).append(" MHz");
            if (gpuBusy != null) sb.append(" (Uso ").append(gpuBusy).append(")");
        } else {
            String exynosClock = readAnyFreqMhz("/sys/kernel/gpu/gpu_clock");
            String exynosBusy = readFirstLine("/sys/kernel/gpu/gpu_busy");
            String exynosModel = readFirstLine("/sys/kernel/gpu/gpu_model");
            if (exynosClock != null) {
                sb.append(exynosModel != null ? "GPU " + exynosModel : "GPU").append(": ").append(exynosClock).append(" MHz");
                if (exynosBusy != null) sb.append(" (Uso ").append(exynosBusy).append(")");
            } else {
                sb.append("GPU               : Restrito por SELinux / N/A");
            }
        }
        sb.append("\n");

        Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        int scale = 100;
        int voltageMv = -1;
        int plugged = 0;
        int status = -1;
        if (sticky != null) {
            level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            voltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        }

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        long rawCurrent = Long.MIN_VALUE;
        if (bm != null) {
            rawCurrent = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        }

        sb.append("Nivel / Estado    : ");
        if (level >= 0 && scale > 0) sb.append((level * 100) / scale).append("%");
        else sb.append("--");
        sb.append(" | ").append(describeBatteryState(status, plugged)).append("\n");

        sb.append("Tensao Bateria    : ");
        if (voltageMv > 0) sb.append(String.format(Locale.US, "%.3f V (%d mV)", voltageMv / 1000.0, voltageMv));
        else sb.append("--");
        sb.append("\n");

        sb.append("Corrente / Potenc.: ");
        if (rawCurrent != Long.MIN_VALUE && rawCurrent != 0L) {
            double ma = normalizeCurrentMa(rawCurrent);
            if (voltageMv > 0) {
                double watts = (voltageMv / 1000.0) * (ma / 1000.0);
                sb.append(String.format(Locale.US, "%+.0f mA (%+.2f W)", ma, watts));
            } else {
                sb.append(String.format(Locale.US, "%+.0f mA", ma));
            }
        } else if (rawCurrent == 0L) {
            sb.append("0 mA");
        } else {
            sb.append("Indisponivel");
        }

        return sb.toString().trim();
    }

    /** Samsung One UI geralmente retorna mA enquanto AOSP retorna uA (&gt; 25000). */
    public static double normalizeCurrentMa(long rawCurrent) {
        return Math.abs(rawCurrent) > 25000L ? (rawCurrent / 1000.0) : rawCurrent;
    }

    private static synchronized String discoverDdrDevfreqPath() {
        if (ddrDevfreqScanned) return discoveredDdrDevfreqCur;
        ddrDevfreqScanned = true;
        File dir = new File("/sys/class/devfreq");
        File[] items = dir.listFiles();
        if (items == null) return null;
        for (File f : items) {
            String name = f.getName().toLowerCase(Locale.US);
            if (name.contains("ddr") || name.contains("mif") || name.contains("mem")) {
                File cur = new File(f, "cur_freq");
                if (cur.canRead()) {
                    discoveredDdrDevfreqCur = cur.getAbsolutePath();
                    return discoveredDdrDevfreqCur;
                }
            }
        }
        return null;
    }

    private static synchronized void scanThermalZonesOnce() {
        if (thermalZonesScanned) return;
        thermalZonesScanned = true;
        File dir = new File("/sys/class/thermal");
        File[] children = dir.listFiles();
        if (children == null) {
            thermalZonesReadable = false;
            return;
        }
        for (File zone : children) {
            String name = zone.getName();
            if (!name.startsWith("thermal_zone")) continue;
            String type = readFirstLine(new File(zone, "type").getAbsolutePath());
            if (type == null) continue;
            thermalZonesReadable = true;
            String tempPath = new File(zone, "temp").getAbsolutePath();
            String lower = type.toLowerCase(Locale.US);
            if (("ddr".equals(lower) || lower.contains("dram")) && ddrThermalPath == null) {
                ddrThermalPath = tempPath;
            } else if ("battery".equals(lower) && batThermalPath == null) {
                batThermalPath = tempPath;
            } else if ((lower.startsWith("gpuss-") || lower.contains("g3d") || "gpu".equals(lower)) && gpuThermalPath == null) {
                gpuThermalPath = tempPath;
            } else if (lower.startsWith("cpuss-") || lower.startsWith("cpu-") || lower.contains("big") || lower.contains("little")) {
                cpuThermalPaths.add(tempPath);
            }
        }
    }

    private static Map<String, String> readThermalServiceTemps() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        String dumpText = dumpSystemService("thermalservice");
        if (dumpText == null || dumpText.isEmpty()) return result;
        String[] lines = dumpText.split("\\r?\\n");
        for (String line : lines) {
            Matcher m = THERMAL_LINE_PATTERN.matcher(line);
            if (m.find()) {
                String value = m.group(1).trim();
                String name = m.group(3).trim().toUpperCase(Locale.US);
                if (!result.containsKey(name)) {
                    result.put(name, value);
                }
            }
        }
        return result;
    }

    private static String dumpSystemService(String serviceName) {
        ParcelFileDescriptor readSide = null;
        ParcelFileDescriptor writeSide = null;
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object binderObj = getService.invoke(null, serviceName);
            if (!(binderObj instanceof IBinder)) return null;
            IBinder binder = (IBinder) binderObj;
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readSide = pipe[0];
            writeSide = pipe[1];

            final ParcelFileDescriptor finalRead = readSide;
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(finalRead);
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    } catch (Exception ignored) {
                    } finally {
                        try { is.close(); } catch (Exception ignored) {}
                    }
                }
            }, "omnia-thermal-dump");
            reader.setDaemon(true);
            reader.start();

            readSide = null;
            try {
                binder.dump(writeSide.getFileDescriptor(), new String[0]);
            } finally {
                try { writeSide.close(); } catch (Exception ignored) {}
                writeSide = null;
            }
            reader.join(800);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (readSide != null) { try { readSide.close(); } catch (Exception ignored) {} }
            if (writeSide != null) { try { writeSide.close(); } catch (Exception ignored) {} }
        }
    }

    private static Map<String, Long> readProcMemInfoKb() {
        Map<String, Long> map = new LinkedHashMap<String, Long>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String rest = line.substring(colon + 1).trim();
                int space = rest.indexOf(' ');
                String numStr = space > 0 ? rest.substring(0, space) : rest;
                try { map.put(key, Long.parseLong(numStr)); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (Exception ignored) {} }
        }
        return map;
    }

    private static Double readMaxThermalCelsius(List<String> paths) {
        Double max = null;
        for (String path : paths) {
            Double val = readThermalCelsius(path);
            if (val != null && (max == null || val > max)) max = val;
        }
        return max;
    }

    private static Double readThermalCelsius(String path) {
        Long raw = readLong(path);
        if (raw == null || raw <= -100000L) return null;
        if (Math.abs(raw) >= 1000L) return raw / 1000.0;
        return raw.doubleValue();
    }

    private static Float readBatteryTempSticky(Context context) {
        Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky == null) return null;
        int tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenths == Integer.MIN_VALUE) return null;
        return tenths / 10.0f;
    }

    private static String describeBatteryState(int status, int plugged) {
        String plugLabel = "";
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plugLabel = "AC";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plugLabel = "USB";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plugLabel = "Wireless";

        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return plugLabel.isEmpty() ? "Carregando" : "Carregando (" + plugLabel + ")";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "Completa";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "Descarregando";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return plugLabel.isEmpty() ? "Nao carregando" : "Conectada (" + plugLabel + ")";
            default:
                return "Desconhecido";
        }
    }

    private static String buildAsciiBar(double ratio, int width) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) bar.append(i < filled ? '#' : '-');
        bar.append("]");
        return bar.toString();
    }

    private static String readFreqGhz(String path) {
        Long khz = readLong(path);
        if (khz == null) return null;
        return String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0);
    }

    private static String readFreqMhz(String path) {
        Long khz = readLong(path);
        if (khz == null) return null;
        return String.valueOf(khz / 1000L);
    }

    private static String readAnyFreqMhz(String path) {
        Long raw = readLong(path);
        if (raw == null) return null;
        if (raw > 10_000_000L) return String.valueOf(raw / 1_000_000L); // Hz -> MHz
        if (raw > 20_000L) return String.valueOf(raw / 1000L);          // kHz -> MHz
        return String.valueOf(raw);                                     // MHz
    }

    private static Long readLong(String path) {
        String s = readFirstLine(path);
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return null; }
    }

    private static String readFirstLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (Exception ignored) {} }
        }
    }

    private static double kbToGib(long kb) { return kb / (1024.0 * 1024.0); }
    private static double bytesToGib(long bytes) { return bytes / (1024.0 * 1024.0 * 1024.0); }
}

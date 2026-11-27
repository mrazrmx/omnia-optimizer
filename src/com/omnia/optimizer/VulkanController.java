package com.omnia.optimizer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native HWUI Vulkan/OpenGL controller over Shizuku: Light / OpenApps / Full / Revert plans,
 * IME + Wallpaper + Widget preservation, Accessibility/AutoRotate/Edge panel state capture & restore,
 * and 60s SystemUI GoodLock cooldown.
 */
public class VulkanController {
    private static final String PROP_RENDERER = "debug.hwui.renderer";
    private static final String PKG_SYSTEM_UI = "com.android.systemui";
    private static final String MEDIA_PROVIDER_MODULE = "providers.media.module";
    private static final String SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23";

    private static final String PREF_NAME = "omnia_vulkan_prefs";
    private static final String KEY_LAST_SYSTEMUI_RESTART_MS = "last_systemui_restart_ms";
    private static final String KEY_KEPT_AT_MS = "kept_settings_at_ms";
    private static final String KEY_KEPT_PREFIX = "kept_";
    private static final long SYSTEMUI_COOLDOWN_MS = 60_000L;
    private static final long KEPT_TTL_MS = 3_600_000L;

    // Critical system/connectivity/DRM packages that must never be force-stopped.
    private static final Set<String> FIXED_EXCLUSIONS = new HashSet<String>(Arrays.asList(
            "com.samsung.android.bluelightfilter",
            "com.samsung.android.wcmurlsnetworkstack",
            "com.sec.unifiedwfc",
            "com.samsung.android.net.wifi.wifiguider",
            "com.sec.imsservice",
            "com.samsung.ims.smk",
            "com.sec.epdg",
            "com.samsung.android.networkstack",
            "com.samsung.android.networkdiagnostic",
            "com.samsung.android.ConnectivityOverlay",
            "com.netflix.mediaclient"
    ));

    private static final Pattern PKG_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9_.]+");
    private static final Pattern COMPONENT_PATTERN = Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");
    private static final Pattern PLAIN_SETTING_PATTERN = Pattern.compile("^[A-Za-z0-9_.$/:;,|\\-]{1,2000}$");

    public enum PipelineState {
        VULKAN,
        OPENGL,
        NOT_DRAWN,
        UNKNOWN
    }

    public enum ApplyMode {
        LIGHT_WITH_SYSTEM_UI,
        LIGHT_DEFER_SYSTEM_UI,
        OPEN_APPS_BEHIND,
        FULL_WITH_SYSTEM_UI,
        FULL_DEFER_SYSTEM_UI,
        REVERT_WITH_SYSTEM_UI,
        REVERT_DEFER_SYSTEM_UI
    }

    private enum RestoredKey {
        AUTO_ROTATION("system", "accelerometer_rotation"),
        ACCESSIBILITY_SERVICES("secure", "enabled_accessibility_services"),
        EDGE_ENABLED("secure", "edge_enable"),
        EDGE_PANELS("secure", "edge_panels_enabled");

        final String namespace;
        final String key;

        RestoredKey(String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
        }
    }

    public static class RendererStatus {
        public boolean propVulkan;
        public String propValue = "";
        public PipelineState systemUi = PipelineState.UNKNOWN;
        public PipelineState launcher = PipelineState.UNKNOWN;
        public PipelineState keyboard = PipelineState.UNKNOWN;
        public String launcherPkg = "";
        public String keyboardId = "";
        public String keyboardPkg = "";
        public boolean behindScanned = false;
        public final List<String> behindOpenPackages = new ArrayList<String>();
        public final List<String> behindOpenLabels = new ArrayList<String>();
    }

    public static class ApplyResult {
        public boolean ok;
        public String summary = "";
        public RendererStatus status;
    }

    public static RendererStatus inspectStatus(Context context, boolean queryGfxViaShizuku) {
        RendererStatus st = new RendererStatus();
        st.propValue = AnalyticsEngine.getSystemProp(PROP_RENDERER, "").trim();
        st.propVulkan = "skiavk".equalsIgnoreCase(st.propValue);
        st.launcherPkg = resolveDefaultLauncherPackage(context);
        st.keyboardId = sanitizeSettingValue(
                AnalyticsEngine.safeSecure(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
        st.keyboardPkg = packageFromComponent(st.keyboardId);

        if (queryGfxViaShizuku && ShizukuBridge.ensureAttached(context)) {
            String propOut = ShizukuBridge.exec(context, "getprop " + PROP_RENDERER, 4000L).stdout;
            if (propOut != null) {
                String trimmedProp = propOut.trim();
                if (!trimmedProp.isEmpty() || !st.propValue.isEmpty()) {
                    st.propValue = trimmedProp;
                    st.propVulkan = "skiavk".equalsIgnoreCase(st.propValue);
                }
            }

            String shellLauncher = resolveHomePackageViaShell(context);
            if (!shellLauncher.isEmpty()) {
                st.launcherPkg = shellLauncher;
            }

            String shellIme = sanitizeSettingValue(
                    ShizukuBridge.exec(context, "settings get secure default_input_method", 4000L).stdout);
            if (!shellIme.isEmpty()) {
                st.keyboardId = shellIme;
                st.keyboardPkg = packageFromComponent(shellIme);
            }

            st.systemUi = inspectPackagePipeline(context, PKG_SYSTEM_UI);
            if (!st.launcherPkg.isEmpty()) {
                st.launcher = inspectPackagePipeline(context, st.launcherPkg);
            }
            if (!st.keyboardPkg.isEmpty()) {
                st.keyboard = inspectPackagePipeline(context, st.keyboardPkg);
            }

            PipelineState target = st.propVulkan ? PipelineState.VULKAN : PipelineState.OPENGL;
            List<String> behind = readBehindOpenPackages(context, st.launcherPkg, st.keyboardPkg, target);
            st.behindScanned = behind != null;
            if (behind != null) {
                st.behindOpenPackages.addAll(behind);
                st.behindOpenLabels.addAll(resolveSortedLabels(context, behind));
            }
        }
        return st;
    }

    /**
     * Backward-compatible wrapper mapping boolean flags to ApplyMode.
     */
    public static ApplyResult applyRenderer(
            Context context,
            boolean enableVulkan,
            boolean restartUserApps,
            boolean restartSystemUi) {
        ApplyMode mode;
        if (!enableVulkan) {
            mode = restartSystemUi ? ApplyMode.REVERT_WITH_SYSTEM_UI : ApplyMode.REVERT_DEFER_SYSTEM_UI;
        } else if (restartUserApps) {
            mode = restartSystemUi ? ApplyMode.FULL_WITH_SYSTEM_UI : ApplyMode.FULL_DEFER_SYSTEM_UI;
        } else {
            mode = restartSystemUi ? ApplyMode.LIGHT_WITH_SYSTEM_UI : ApplyMode.LIGHT_DEFER_SYSTEM_UI;
        }
        return applyMode(context, mode);
    }

    public static ApplyResult applyMode(Context context, ApplyMode mode) {
        ApplyResult out = new ApplyResult();
        if (!ShizukuBridge.ensureAttached(context)) {
            out.ok = false;
            out.summary = "Shizuku offline. Conecte e autorize o Shizuku primeiro.";
            return out;
        }

        boolean isOpenAppsOnly = (mode == ApplyMode.OPEN_APPS_BEHIND);
        boolean isRevert = (mode == ApplyMode.REVERT_WITH_SYSTEM_UI || mode == ApplyMode.REVERT_DEFER_SYSTEM_UI);
        boolean isFull = (mode == ApplyMode.FULL_WITH_SYSTEM_UI || mode == ApplyMode.FULL_DEFER_SYSTEM_UI);
        boolean withSystemUi = (mode == ApplyMode.LIGHT_WITH_SYSTEM_UI
                || mode == ApplyMode.FULL_WITH_SYSTEM_UI
                || mode == ApplyMode.REVERT_WITH_SYSTEM_UI);

        StringBuilder log = new StringBuilder();
        RendererStatus before = inspectStatus(context, true);
        PipelineState target = isOpenAppsOnly
                ? (before.propVulkan ? PipelineState.VULKAN : PipelineState.OPENGL)
                : (isRevert ? PipelineState.OPENGL : PipelineState.VULKAN);

        String selfPkg = context.getPackageName();
        String shizukuPkg = resolveShizukuPackage(context);
        String launcherPkg = before.launcherPkg;
        String savedKeyboardId = before.keyboardId;
        String keyboardPkg = before.keyboardPkg;

        Map<RestoredKey, String> captured = Collections.emptyMap();
        List<String> stopTargets = new ArrayList<String>();
        List<String> relaunchTargets = new ArrayList<String>();

        if (isFull || isOpenAppsOnly) {
            captured = captureDeviceSettings(context);
            keepSettings(context, captured);

            Set<String> imes = readInstalledImes(context);
            String wallpaperPkg = readWallpaperPackage(context);
            Set<String> kept = buildKeptSet(imes, keyboardPkg, wallpaperPkg, shizukuPkg, selfPkg);
            kept.add(PKG_SYSTEM_UI);

            if (isOpenAppsOnly) {
                for (String pkg : before.behindOpenPackages) {
                    if (!kept.contains(pkg) && !isBlacklisted(pkg) && !pkg.equals(launcherPkg)) {
                        stopTargets.add(pkg);
                    }
                }
                if (stopTargets.isEmpty()) {
                    letGoKeptSettings(context);
                    out.ok = true;
                    out.status = before;
                    out.summary = "✅ Todos os apps abertos já estão em "
                            + (target == PipelineState.VULKAN ? "Vulkan" : "OpenGL") + ".";
                    return out;
                }
                relaunchTargets.addAll(stopTargets);
            } else {
                List<String> installed = readInstalledPackages(context);
                if (installed.isEmpty()) {
                    letGoKeptSettings(context);
                    out.ok = false;
                    out.summary = "Falha ao listar pacotes via pm list packages.";
                    out.status = before;
                    return out;
                }
                Set<String> installedSet = new LinkedHashSet<String>(installed);
                Set<String> running = readRunningPackages(context, installedSet);
                Set<String> widgetPkgs = readWidgetPackages(context);
                Set<String> wanted = new LinkedHashSet<String>(running);
                wanted.addAll(widgetPkgs);

                boolean launcherNeedsRestart = !launcherPkg.isEmpty() && before.launcher != target;
                Set<String> stopKept = new HashSet<String>(kept);
                if (!launcherNeedsRestart && !launcherPkg.isEmpty()) {
                    stopKept.add(launcherPkg);
                }

                for (String pkg : installed) {
                    if (mayStop(pkg, stopKept)) {
                        stopTargets.add(pkg);
                        if (wanted.contains(pkg) && !pkg.equals(launcherPkg)) {
                            relaunchTargets.add(pkg);
                        }
                    }
                }
            }
        }

        boolean failed = false;
        try {
            if (!isOpenAppsOnly) {
                boolean propNeedsChange = (target == PipelineState.VULKAN && !before.propVulkan)
                        || (target == PipelineState.OPENGL && !before.propValue.isEmpty());
                if (propNeedsChange) {
                    String val = (target == PipelineState.VULKAN) ? "skiavk" : "''";
                    ShizukuBridge.ExecResult propRes = ShizukuBridge.exec(
                            context, "setprop " + PROP_RENDERER + " " + val + "; getprop " + PROP_RENDERER, 6000L);
                    String readBack = propRes.stdout.trim();
                    boolean matched = (target == PipelineState.VULKAN)
                            ? "skiavk".equalsIgnoreCase(readBack)
                            : readBack.isEmpty();
                    if (!propRes.isOk() || !matched) {
                        log.append("❌ Falha em debug.hwui.renderer (").append(readBack).append(")\n");
                        failed = true;
                    } else {
                        log.append(target == PipelineState.VULKAN
                                ? "✅ debug.hwui.renderer = skiavk gravado\n"
                                : "✅ debug.hwui.renderer limpo (padrão OpenGL)\n");
                    }
                } else {
                    log.append("ℹ️ Propriedade HWUI já estava no alvo.\n");
                }
            }

            if (!failed && !stopTargets.isEmpty()) {
                int stopped = stopPackagesInBatches(context, stopTargets);
                log.append("🔄 Apps reiniciados: ").append(stopped).append("\n");
            }

            if (!failed && !isOpenAppsOnly && !launcherPkg.isEmpty()
                    && before.launcher != target && !isBlacklisted(launcherPkg) && !launcherPkg.equals(selfPkg)) {
                ShizukuBridge.exec(context,
                        "am force-stop " + shellQuote(launcherPkg)
                        + "; sleep 2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true",
                        10_000L);
                log.append("🏠 Launcher reiniciado (").append(launcherPkg).append(")\n");
            }

            if (!failed && !relaunchTargets.isEmpty()) {
                relaunchPackagesInBatches(context, relaunchTargets, selfPkg);
                log.append("🚀 Apps/widgets reabertos: ").append(relaunchTargets.size()).append("\n");
            }

            if (!failed && !isOpenAppsOnly && !keyboardPkg.isEmpty()
                    && keyboardNeedsRestart(before.keyboard, target) && !isBlacklisted(keyboardPkg)) {
                restartKeyboardKeepingDefault(context, keyboardPkg, savedKeyboardId, log);
            }
        } finally {
            if (!captured.isEmpty()) {
                boolean allRestored = restoreDeviceSettings(context, captured);
                if (allRestored) {
                    letGoKeptSettings(context);
                    log.append("🛡️ Acessibilidade / Rotação / Edge restaurados.\n");
                } else {
                    failed = true;
                    log.append("⚠️ Parte das configurações restauradas localmente; mantendo backup.\n");
                }
            }
        }

        if (!failed && !isOpenAppsOnly && before.systemUi != target) {
            if (withSystemUi) {
                if (claimSystemUiCooldown(context)) {
                    boolean verified = restartSystemUiAndVerify(context, target);
                    log.append(verified
                            ? "🔒 System UI reiniciada e confirmada no novo pipeline\n"
                            : "⚠️ System UI reiniciada (verifique o status em alguns segundos)\n");
                } else {
                    log.append("⏳ System UI mantida (cooldown 60s protege módulos Good Lock)\n");
                }
            } else {
                log.append("ℹ️ System UI mantida sem travar tela agora (assumirá no próximo bloqueio).\n");
            }
        }

        out.status = inspectStatus(context, true);
        out.ok = !failed;
        out.summary = log.toString().trim();
        return out;
    }

    /** Restores any kept settings if a previous full apply terminated abnormally within KEPT_TTL_MS. */
    public static void restorePendingKeptSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long keptAt = prefs.getLong(KEY_KEPT_AT_MS, 0L);
        if (keptAt <= 0L) return;
        long now = System.currentTimeMillis();
        if (now - keptAt <= KEPT_TTL_MS) {
            Map<RestoredKey, String> kept = new LinkedHashMap<RestoredKey, String>();
            for (RestoredKey rk : RestoredKey.values()) {
                String val = prefs.getString(KEY_KEPT_PREFIX + rk.name(), null);
                if (val != null && !val.isEmpty()) {
                    kept.put(rk, val);
                }
            }
            if (!kept.isEmpty()) {
                restoreViaContentResolver(context, kept);
            }
        }
        letGoKeptSettings(context);
    }

    private static void restartKeyboardKeepingDefault(
            Context context, String keyboardPkg, String savedKeyboardId, StringBuilder log) {
        ShizukuBridge.exec(context,
                "am crash \"$(pidof -s " + shellQuote(keyboardPkg) + " || echo " + shellQuote(keyboardPkg) + ")\" >/dev/null 2>&1 || true",
                6000L);
        if (savedKeyboardId != null && !savedKeyboardId.isEmpty()) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            String current = sanitizeSettingValue(
                    ShizukuBridge.exec(context, "settings get secure default_input_method", 4000L).stdout);
            if (!savedKeyboardId.equals(current)) {
                ShizukuBridge.exec(context,
                        "ime enable " + shellQuote(savedKeyboardId) + " >/dev/null 2>&1; "
                        + "ime set " + shellQuote(savedKeyboardId) + " >/dev/null 2>&1",
                        5000L);
                String afterImeSet = sanitizeSettingValue(
                        ShizukuBridge.exec(context, "settings get secure default_input_method", 4000L).stdout);
                if (!savedKeyboardId.equals(afterImeSet)) {
                    ShizukuBridge.exec(context,
                            "settings put secure default_input_method " + shellQuote(savedKeyboardId), 4000L);
                }
            }
        }
        log.append("⌨️ Teclado reiniciado (").append(keyboardPkg).append(")\n");
    }

    private static boolean restartSystemUiAndVerify(Context context, PipelineState target) {
        ShizukuBridge.exec(context,
                "am crash \"$(pidof -s " + PKG_SYSTEM_UI + " || echo " + PKG_SYSTEM_UI + ")\" >/dev/null 2>&1 || true",
                6000L);
        for (int i = 0; i < 8; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            if (inspectPackagePipeline(context, PKG_SYSTEM_UI) == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean claimSystemUiCooldown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_SYSTEMUI_RESTART_MS, 0L);
        if (last > 0L && last <= now && (now - last) < SYSTEMUI_COOLDOWN_MS) {
            return false;
        }
        prefs.edit().putLong(KEY_LAST_SYSTEMUI_RESTART_MS, now).apply();
        return true;
    }

    private static Map<RestoredKey, String> captureDeviceSettings(Context context) {
        ContentResolver cr = context.getContentResolver();
        Map<RestoredKey, String> map = new LinkedHashMap<RestoredKey, String>();
        for (RestoredKey rk : RestoredKey.values()) {
            String val = "system".equals(rk.namespace)
                    ? AnalyticsEngine.safeSystem(cr, rk.key)
                    : AnalyticsEngine.safeSecure(cr, rk.key);
            val = sanitizeSettingValue(val);
            // Security boundary (ApplyInputsReader.kt): never trust shell fallback for AccessibilityServices.
            if (val.isEmpty() && rk != RestoredKey.ACCESSIBILITY_SERVICES) {
                ShizukuBridge.ExecResult res = ShizukuBridge.exec(
                        context, "settings get " + rk.namespace + " " + rk.key, 4000L);
                String candidate = sanitizeSettingValue(res.stdout);
                if (acceptsSettingValue(rk, candidate)) {
                    val = candidate;
                }
            }
            if (!val.isEmpty()) {
                map.put(rk, val);
            }
        }
        return map;
    }

    private static boolean restoreDeviceSettings(Context context, Map<RestoredKey, String> captured) {
        boolean allOk = true;
        for (Map.Entry<RestoredKey, String> entry : captured.entrySet()) {
            RestoredKey rk = entry.getKey();
            String val = entry.getValue();
            if (val == null || val.isEmpty() || "null".equals(val)) continue;
            boolean ok = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
                ShizukuBridge.ExecResult putRes = ShizukuBridge.exec(
                        context, "settings put " + rk.namespace + " " + rk.key + " " + shellQuote(val), 5000L);
                if (putRes.isOk()) {
                    String check = sanitizeSettingValue(
                            ShizukuBridge.exec(context, "settings get " + rk.namespace + " " + rk.key, 4000L).stdout);
                    if (val.equals(check)) {
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                Map<RestoredKey, String> single = new LinkedHashMap<RestoredKey, String>();
                single.put(rk, val);
                ok = restoreViaContentResolver(context, single);
            }
            if (!ok) allOk = false;
        }
        return allOk;
    }

    private static boolean restoreViaContentResolver(Context context, Map<RestoredKey, String> kept) {
        ContentResolver cr = context.getContentResolver();
        boolean allOk = true;
        for (Map.Entry<RestoredKey, String> e : kept.entrySet()) {
            if (!acceptsSettingValue(e.getKey(), e.getValue())) continue;
            try {
                boolean ok = "system".equals(e.getKey().namespace)
                        ? Settings.System.putString(cr, e.getKey().key, e.getValue())
                        : Settings.Secure.putString(cr, e.getKey().key, e.getValue());
                if (!ok) allOk = false;
            } catch (Throwable ignored) {
                allOk = false;
            }
        }
        return allOk;
    }

    private static void keepSettings(Context context, Map<RestoredKey, String> values) {
        if (values.isEmpty()) return;
        SharedPreferences.Editor ed = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putLong(KEY_KEPT_AT_MS, System.currentTimeMillis());
        for (Map.Entry<RestoredKey, String> e : values.entrySet()) {
            ed.putString(KEY_KEPT_PREFIX + e.getKey().name(), e.getValue());
        }
        ed.commit();
    }

    private static void letGoKeptSettings(Context context) {
        SharedPreferences.Editor ed = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.remove(KEY_KEPT_AT_MS);
        for (RestoredKey rk : RestoredKey.values()) {
            ed.remove(KEY_KEPT_PREFIX + rk.name());
        }
        ed.apply();
    }

    private static boolean acceptsSettingValue(RestoredKey rk, String val) {
        if (val == null || val.isEmpty() || "null".equals(val)) return false;
        switch (rk) {
            case AUTO_ROTATION:
            case EDGE_ENABLED:
                return "0".equals(val) || "1".equals(val);
            case ACCESSIBILITY_SERVICES:
                for (String part : val.split(":")) {
                    if (!COMPONENT_PATTERN.matcher(part).matches()) return false;
                }
                return true;
            case EDGE_PANELS:
                return PLAIN_SETTING_PATTERN.matcher(val).matches();
        }
        return false;
    }

    private static PipelineState inspectPackagePipeline(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return PipelineState.UNKNOWN;
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(
                context, "dumpsys gfxinfo " + shellQuote(pkg), 8000L);
        if (!res.isOk() && res.stdout.isEmpty()) return PipelineState.UNKNOWN;
        return parseSinglePackageRenderer(res.stdout);
    }

    private static PipelineState parseSinglePackageRenderer(String stdout) {
        if (stdout == null || stdout.isEmpty()) return PipelineState.UNKNOWN;
        boolean sawProcessHeader = false;
        for (String line : stdout.split("\\r?\\n")) {
            if (line.startsWith("** Graphics info for pid ")) {
                sawProcessHeader = true;
            }
            int idx = line.indexOf("Pipeline=");
            if (idx >= 0) {
                String pipe = line.substring(idx + "Pipeline=".length()).trim();
                if ("Skia (Vulkan)".equals(pipe) || pipe.toLowerCase(Locale.US).contains("vulkan")) {
                    return PipelineState.VULKAN;
                }
                if ("Skia (OpenGL)".equals(pipe) || pipe.toLowerCase(Locale.US).contains("opengl")) {
                    return PipelineState.OPENGL;
                }
                return PipelineState.UNKNOWN;
            }
        }
        return sawProcessHeader ? PipelineState.NOT_DRAWN : PipelineState.UNKNOWN;
    }

    private static List<String> readBehindOpenPackages(
            Context context, String launcherPkg, String keyboardPkg, PipelineState target) {
        ShizukuBridge.ExecResult gfxRes = ShizukuBridge.exec(context, "dumpsys gfxinfo", 12_000L);
        if (!gfxRes.isOk() && gfxRes.stdout.isEmpty()) {
            return null;
        }
        Map<String, PipelineState> procs = new LinkedHashMap<String, PipelineState>();
        String curProc = null;
        for (String line : gfxRes.stdout.split("\\r?\\n")) {
            if (line.startsWith("** Graphics info for pid ")) {
                int ob = line.indexOf('[');
                int cb = line.indexOf(']', ob + 1);
                curProc = (ob >= 0 && cb > ob + 1) ? line.substring(ob + 1, cb).trim() : null;
            } else if (curProc != null && line.startsWith("Pipeline=")) {
                if (!procs.containsKey(curProc)) {
                    procs.put(curProc, parseSinglePackageRenderer(line));
                }
            }
        }

        Set<String> imes = readInstalledImes(context);
        String wallpaperPkg = readWallpaperPackage(context);
        String shizukuPkg = resolveShizukuPackage(context);
        Set<String> kept = buildKeptSet(imes, keyboardPkg, wallpaperPkg, shizukuPkg, context.getPackageName());
        kept.add(PKG_SYSTEM_UI);
        if (launcherPkg != null && !launcherPkg.isEmpty()) kept.add(launcherPkg);

        LinkedHashSet<String> behind = new LinkedHashSet<String>();
        for (Map.Entry<String, PipelineState> entry : procs.entrySet()) {
            PipelineState ps = entry.getValue();
            if (ps == PipelineState.UNKNOWN || ps == PipelineState.NOT_DRAWN || ps == target) continue;
            String rawName = entry.getKey();
            int colon = rawName.indexOf(':');
            String pkg = colon > 0 ? rawName.substring(0, colon) : rawName;
            if (!PKG_PATTERN.matcher(pkg).matches()) continue;
            if (mayStop(pkg, kept)) {
                behind.add(pkg);
            }
        }
        return new ArrayList<String>(behind);
    }

    private static List<String> readInstalledPackages(Context context) {
        // pm list packages may print SecurityException for Secure Folder user 150 on stderr while still listing user 0.
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "pm list packages", 10_000L);
        List<String> list = new ArrayList<String>();
        if (res.stdout.isEmpty()) return list;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String line : res.stdout.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("package:")) continue;
            String pkg = trimmed.substring("package:".length()).trim();
            if (PKG_PATTERN.matcher(pkg).matches()) {
                seen.add(pkg);
            }
        }
        list.addAll(seen);
        return list;
    }

    private static Set<String> readInstalledImes(Context context) {
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "ime list -s", 5000L);
        Set<String> imes = new HashSet<String>();
        for (String line : res.stdout.split("\\r?\\n")) {
            String pkg = packageFromComponent(line);
            if (!pkg.isEmpty() && PKG_PATTERN.matcher(pkg).matches()) {
                imes.add(pkg);
            }
        }
        return imes;
    }

    private static String readWallpaperPackage(Context context) {
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "dumpsys wallpaper", 8000L);
        for (String line : res.stdout.split("\\r?\\n")) {
            int idx = line.indexOf("mWallpaperComponent=");
            if (idx >= 0) {
                int start = line.indexOf("ComponentInfo{", idx);
                int slash = start >= 0 ? line.indexOf('/', start) : -1;
                if (start >= 0 && slash > start + "ComponentInfo{".length()) {
                    String pkg = line.substring(start + "ComponentInfo{".length(), slash).trim();
                    if (PKG_PATTERN.matcher(pkg).matches()) return pkg;
                }
            }
        }
        return "";
    }

    private static Set<String> readRunningPackages(Context context, Set<String> candidates) {
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "dumpsys activity processes", 10_000L);
        Set<String> running = new HashSet<String>();
        if (res.stdout.isEmpty() || candidates.isEmpty()) return running;
        for (String token : TOKEN_SPLIT.split(res.stdout)) {
            if (candidates.contains(token)) {
                running.add(token);
            }
        }
        return running;
    }

    private static Set<String> readWidgetPackages(Context context) {
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "dumpsys appwidget", 8000L);
        Set<String> pkgs = new HashSet<String>();
        String section = "";
        for (String line : res.stdout.split("\\r?\\n")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                section = line.trim();
            } else if ("Widgets:".equals(section) && line.contains("provider=")) {
                int s = line.indexOf("ComponentInfo{");
                int slash = s >= 0 ? line.indexOf('/', s) : -1;
                if (s >= 0 && slash > s + "ComponentInfo{".length()) {
                    String pkg = line.substring(s + "ComponentInfo{".length(), slash).trim();
                    if (PKG_PATTERN.matcher(pkg).matches()) pkgs.add(pkg);
                }
            } else if ("Hosts:".equals(section) && line.contains("hostId=HostId")) {
                int s = line.indexOf("pkg:");
                int e = s >= 0 ? line.indexOf('}', s) : -1;
                if (s >= 0 && e > s + "pkg:".length()) {
                    String pkg = line.substring(s + "pkg:".length(), e).trim();
                    if (PKG_PATTERN.matcher(pkg).matches()) pkgs.add(pkg);
                }
            }
        }
        return pkgs;
    }

    private static String resolveHomePackageViaShell(Context context) {
        ShizukuBridge.ExecResult resolved = ShizukuBridge.exec(
                context, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 5000L);
        String[] lines = resolved.stdout.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].contains("/")) {
                String pkg = packageFromComponent(lines[i]);
                if (!pkg.isEmpty() && !"android".equals(pkg) && PKG_PATTERN.matcher(pkg).matches()) {
                    return pkg;
                }
            }
        }
        ShizukuBridge.ExecResult holders = ShizukuBridge.exec(
                context, "cmd role get-role-holders android.app.role.HOME", 5000L);
        for (String part : holders.stdout.split("[;\\r\\n]+")) {
            String pkg = part.trim();
            if (PKG_PATTERN.matcher(pkg).matches()) {
                return pkg;
            }
        }
        return "";
    }

    private static String resolveShizukuPackage(Context context) {
        try {
            PermissionInfo pi = context.getPackageManager().getPermissionInfo(SHIZUKU_PERMISSION, 0);
            if (pi != null && pi.packageName != null && !pi.packageName.isEmpty()) {
                return pi.packageName;
            }
        } catch (Throwable ignored) {}
        return ShizukuBridge.SHIZUKU_PACKAGE;
    }

    private static Set<String> buildKeptSet(
            Set<String> imes, String activeKeyboardPkg, String wallpaperPkg, String shizukuPkg, String selfPkg) {
        Set<String> kept = new HashSet<String>();
        if (imes != null) kept.addAll(imes);
        if (activeKeyboardPkg != null && !activeKeyboardPkg.isEmpty()) kept.add(activeKeyboardPkg);
        if (wallpaperPkg != null && !wallpaperPkg.isEmpty()) kept.add(wallpaperPkg);
        kept.addAll(FIXED_EXCLUSIONS);
        if (shizukuPkg != null && !shizukuPkg.isEmpty()) kept.add(shizukuPkg);
        if (selfPkg != null && !selfPkg.isEmpty()) kept.add(selfPkg);
        return kept;
    }

    private static boolean mayStop(String pkg, Set<String> kept) {
        return !kept.contains(pkg) && !pkg.contains(MEDIA_PROVIDER_MODULE);
    }

    private static boolean isBlacklisted(String pkg) {
        return FIXED_EXCLUSIONS.contains(pkg) || pkg.contains(MEDIA_PROVIDER_MODULE);
    }

    private static boolean keyboardNeedsRestart(PipelineState now, PipelineState target) {
        return now != target && now != PipelineState.UNKNOWN;
    }

    private static int stopPackagesInBatches(Context context, List<String> packages) {
        String header = "stop_app() { am stop-app \"$1\" >/dev/null 2>&1 || am force-stop \"$1\"; }; ";
        int count = 0;
        final int batchSize = 35;
        for (int i = 0; i < packages.size(); i += batchSize) {
            StringBuilder sb = new StringBuilder(header);
            int end = Math.min(packages.size(), i + batchSize);
            for (int j = i; j < end; j++) {
                sb.append("stop_app ").append(shellQuote(packages.get(j))).append("; ");
                count++;
            }
            sb.append("true");
            ShizukuBridge.exec(context, sb.toString(), 20_000L);
        }
        return count;
    }

    private static void relaunchPackagesInBatches(Context context, List<String> packages, String frontPkg) {
        List<String> ordered = new ArrayList<String>(packages);
        if (frontPkg != null && !frontPkg.isEmpty()) ordered.add(frontPkg);
        final int batchSize = 20;
        for (int i = 0; i < ordered.size(); i += batchSize) {
            StringBuilder sb = new StringBuilder();
            int end = Math.min(ordered.size(), i + batchSize);
            for (int j = i; j < end; j++) {
                String q = shellQuote(ordered.get(j));
                sb.append("c=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                  .append(q)
                  .append(" | tail -n 1); case \"$c\" in */*) am start -n \"$c\" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null 2>&1;; esac; ");
            }
            sb.append("true");
            ShizukuBridge.exec(context, sb.toString(), 25_000L);
        }
    }

    private static List<String> resolveSortedLabels(Context context, List<String> packages) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> byPkg = new HashMap<String, String>();
        for (String pkg : packages) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) {
                    byPkg.put(pkg, cs.toString());
                }
            } catch (Throwable ignored) {}
        }
        List<String> labels = new ArrayList<String>(byPkg.values());
        Collections.sort(labels, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareToIgnoreCase(b);
            }
        });
        return labels;
    }

    public static String resolveDefaultLauncherPackage(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                String p = ri.activityInfo.packageName;
                if (!"android".equals(p)) return p;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static String packageFromComponent(String component) {
        if (component == null) return "";
        int idx = component.indexOf('/');
        return idx > 0 ? component.substring(0, idx).trim() : "";
    }

    private static String sanitizeSettingValue(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return ("null".equals(t) || t.isEmpty()) ? "" : t;
    }

    public static String describePipeline(PipelineState state) {
        if (state == PipelineState.VULKAN) return "Skia (Vulkan)";
        if (state == PipelineState.OPENGL) return "Skia (OpenGL)";
        if (state == PipelineState.NOT_DRAWN) return "Inativo (sem janela desenhada)";
        return "Não inspecionado / desligado";
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

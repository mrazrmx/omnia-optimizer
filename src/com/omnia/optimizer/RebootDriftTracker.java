package com.omnia.optimizer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detecta sob demanda configurações otimizadas que voltaram ao padrão após um reinício
 * comparando o boot_id atual do kernel contra o baseline salvo na sessão anterior.
 * Nunca aplica ajustes sozinho sem autorização explícita do usuário.
 */
public final class RebootDriftTracker {

    private static final String PREFS = "omnia_drift_tracker_v5";
    private static final String KEY_BASELINE_BOOT_ID = "baseline_boot_id";
    private static final String KEY_BASELINE_VALUES = "baseline_values_json";
    private static final String KEY_DISMISSED_BOOT_ID = "dismissed_boot_id";

    public static final class DriftItem {
        public final String namespace;
        public final String key;
        public final String label;
        public final String expectedValue;
        public final String currentValue;

        public DriftItem(String namespace, String key, String label, String expectedValue, String currentValue) {
            this.namespace = namespace;
            this.key = key;
            this.label = label;
            this.expectedValue = expectedValue;
            this.currentValue = currentValue;
        }
    }

    public static final class DriftReport {
        public String bootId = "";
        public final List<DriftItem> items = new ArrayList<DriftItem>();

        public boolean hasDrift() {
            return !items.isEmpty();
        }

        public String formatSummary() {
            if (items.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                DriftItem it = items.get(i);
                if (i > 0) sb.append("\n");
                sb.append("• ").append(it.label).append(": ")
                  .append(it.expectedValue.isEmpty() ? "alvo" : it.expectedValue)
                  .append(" → ").append(it.currentValue.isEmpty() ? "padrão" : it.currentValue);
            }
            return sb.toString();
        }
    }

    private RebootDriftTracker() {
    }

    public static synchronized DriftReport checkAndUpdate(Context context) {
        DriftReport report = new DriftReport();
        String curBootId = readBootId();
        report.bootId = curBootId;
        if (curBootId.isEmpty()) return report;

        Map<String, String> curVals = captureLocalState(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedBootId = prefs.getString(KEY_BASELINE_BOOT_ID, "");
        String dismissedBoot = prefs.getString(KEY_DISMISSED_BOOT_ID, "");

        if (savedBootId.isEmpty()) {
            saveBaseline(prefs, curBootId, curVals);
            return report;
        }

        if (savedBootId.equals(curBootId) || dismissedBoot.equals(curBootId)) {
            saveBaseline(prefs, curBootId, curVals);
            return report;
        }

        Map<String, String> baseVals = loadJsonMap(prefs.getString(KEY_BASELINE_VALUES, "{}"));
        collectRegressions(baseVals, curVals, report.items);

        if (report.items.isEmpty()) {
            saveBaseline(prefs, curBootId, curVals);
        }
        return report;
    }

    public static synchronized void recordBaselineNow(Context context) {
        String bootId = readBootId();
        if (bootId.isEmpty()) return;
        Map<String, String> vals = captureLocalState(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        saveBaseline(prefs, bootId, vals);
    }

    public static synchronized void dismissDrift(Context context) {
        String bootId = readBootId();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, String> curVals = captureLocalState(context);
        prefs.edit().putString(KEY_DISMISSED_BOOT_ID, bootId).apply();
        saveBaseline(prefs, bootId, curVals);
    }

    public static SystemTweaksController.ActionResult applyPendingDriftFixes(Context context) {
        DriftReport report = checkAndUpdate(context);
        if (!report.hasDrift()) {
            return new SystemTweaksController.ActionResult(true, "✅ Nenhuma regressão pós-reboot pendente.");
        }
        if (!ShizukuBridge.ensureAttached(context) || !ShizukuBridge.hasPermission(context)) {
            return new SystemTweaksController.ActionResult(false, "Ative/autorize o Shizuku primeiro para reaplicar ajustes pós-reboot.");
        }

        StringBuilder shellCmd = new StringBuilder();
        List<String> applied = new ArrayList<String>();
        boolean needVulkan = false;

        for (DriftItem it : report.items) {
            if ("prop".equals(it.namespace) && "debug.hwui.renderer".equals(it.key)) {
                needVulkan = true;
                continue;
            }
            if (shellCmd.length() > 0) shellCmd.append(" && ");
            shellCmd.append("settings put ").append(it.namespace).append(" ")
                    .append(it.key).append(" ").append(it.expectedValue);
            applied.add(it.label);
        }

        if (shellCmd.length() > 0) {
            ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, shellCmd.toString(), 10000L);
            if (!res.isOk()) {
                return new SystemTweaksController.ActionResult(false,
                        "Falha ao reaplicar settings via Shizuku: "
                                + (res.error != null && !res.error.isEmpty() ? res.error : res.stderr));
            }
        }

        if (needVulkan) {
            VulkanController.ApplyResult vkRes = VulkanController.applyMode(
                    context, VulkanController.ApplyMode.LIGHT_DEFER_SYSTEM_UI);
            if (!vkRes.ok) {
                return new SystemTweaksController.ActionResult(false,
                        "Settings restaurados, mas Vulkan falhou: " + vkRes.summary);
            }
            applied.add("Vulkan skiavk (novos apps/Launcher)");
        }

        recordBaselineNow(context);
        return new SystemTweaksController.ActionResult(true,
                "✅ Reaplicado pós-reboot (" + applied.size() + "): " + android.text.TextUtils.join(", ", applied));
    }

    private static void collectRegressions(Map<String, String> base, Map<String, String> cur, List<DriftItem> out) {
        if ("skiavk".equals(base.get("debug.hwui.renderer"))
                && !"skiavk".equals(cur.get("debug.hwui.renderer"))) {
            out.add(new DriftItem("prop", "debug.hwui.renderer", "Renderizador Vulkan",
                    "skiavk", nonEmptyOr(cur.get("debug.hwui.renderer"), "padrão/OpenGL")));
        }
        checkExact(base, cur, "global", "wifi_scan_always_enabled", "0", "Varredura Wi-Fi em 2º plano", out);
        checkExact(base, cur, "global", "ble_scan_always_enabled", "0", "Varredura BLE em 2º plano", out);
        checkExact(base, cur, "system", "nearby_scanning_enabled", "0", "Busca de aparelhos próximos", out);
        checkExact(base, cur, "global", "sem_low_heat_mode", "1", "Perfil Leve (CPU eficiente)", out);
        checkExact(base, cur, "system", "mcf_continuity", "0", "Continuidade Samsung (MCF)", out);
        checkExact(base, cur, "global", "cached_apps_freezer", "enabled", "Congelamento de apps em cache", out);

        String baseAod = base.get("aod_mode");
        String curAod = cur.get("aod_mode");
        if ("0".equals(baseAod) && "1".equals(curAod)) {
            out.add(new DriftItem("system", "aod_mode", "AOD Desligado", "0", "1"));
        } else if ("1".equals(curAod) && "1".equals(base.get("aod_tap_to_show_mode"))
                && !"1".equals(cur.get("aod_tap_to_show_mode"))) {
            out.add(new DriftItem("system", "aod_tap_to_show_mode", "AOD Toque para exibir",
                    "1", nonEmptyOr(cur.get("aod_tap_to_show_mode"), "0")));
        }
    }

    private static void checkExact(Map<String, String> base, Map<String, String> cur,
                                   String ns, String key, String target, String label, List<DriftItem> out) {
        String b = base.get(key);
        String c = cur.get(key);
        if (target.equals(b) && c != null && !c.isEmpty() && !target.equalsIgnoreCase(c)) {
            out.add(new DriftItem(ns, key, label, target, c));
        }
    }

    private static Map<String, String> captureLocalState(Context context) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        ContentResolver cr = context.getContentResolver();
        put(map, "debug.hwui.renderer", AnalyticsEngine.getSystemProp("debug.hwui.renderer", ""));
        put(map, "wifi_scan_always_enabled", AnalyticsEngine.safeGlobal(cr, "wifi_scan_always_enabled"));
        put(map, "ble_scan_always_enabled", AnalyticsEngine.safeGlobal(cr, "ble_scan_always_enabled"));
        put(map, "nearby_scanning_enabled", AnalyticsEngine.safeSystem(cr, "nearby_scanning_enabled"));
        put(map, "sem_low_heat_mode", AnalyticsEngine.safeGlobal(cr, "sem_low_heat_mode"));
        put(map, "aod_mode", AnalyticsEngine.safeSystem(cr, "aod_mode"));
        put(map, "aod_tap_to_show_mode", AnalyticsEngine.safeSystem(cr, "aod_tap_to_show_mode"));
        put(map, "mcf_continuity", AnalyticsEngine.safeSystem(cr, "mcf_continuity"));
        put(map, "cached_apps_freezer", AnalyticsEngine.safeGlobal(cr, "cached_apps_freezer"));
        return map;
    }

    private static void put(Map<String, String> map, String key, String val) {
        if (val != null) {
            map.put(key, val.trim());
        }
    }

    private static String nonEmptyOr(String val, String fallback) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : fallback;
    }

    private static void saveBaseline(SharedPreferences prefs, String bootId, Map<String, String> vals) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : vals.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            prefs.edit()
                    .putString(KEY_BASELINE_BOOT_ID, bootId)
                    .putString(KEY_BASELINE_VALUES, obj.toString())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> loadJsonMap(String json) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        try {
            JSONObject obj = new JSONObject(json != null ? json : "{}");
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, obj.optString(k, ""));
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static String readBootId() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
    }
}

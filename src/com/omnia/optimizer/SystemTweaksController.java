package com.omnia.optimizer;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Display;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
 * Controlador sob demanda de ajustes de eficiência, rádio, continuidade,
 * controle de apps em 2º plano, ART speed-profile, freezer e caches via Shizuku (UID 2000).
 * 0% de execução em background — tudo roda apenas mediante toque explícito do usuário.
 */
public final class SystemTweaksController {

    private static final String PREFS = "omnia_system_tweaks_v5";
    private static final String KEY_ORIG_NET = "orig_preferred_network_mode";
    private static final String KEY_ORIG_MCF_CONT = "orig_mcf_continuity";
    private static final String KEY_ORIG_MCF_MYDEV = "orig_mcf_mydevice";
    private static final String KEY_ORIG_MCF_FAM = "orig_mcf_family";
    private static final String KEY_APP_UNDO_LIST = "app_restriction_undo_json";
    private static final String KEY_LAST_ART_REPORT = "last_art_report";
    private static final String KEY_ORIG_MIN_HZ = "orig_min_refresh_hz";
    private static final String KEY_ORIG_PEAK_HZ = "orig_peak_refresh_hz";
    private static final String KEY_ORIG_REFRESH_MODE = "orig_refresh_rate_mode";

    public static final String MOSEY_PACKAGE = "com.google.android.mosey";

    private static final Set<String> EXACT_PROTECTED_PACKAGES = new HashSet<String>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.providers.telephony",
            "com.android.settings",
            "com.google.android.gms",
            "com.google.android.gsf",
            "moe.shizuku.privileged.api",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.sec.unifiedwfc",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.sec.android.app.clockpackage",
            "com.google.android.deskclock",
            "com.samsung.android.app.watchmanager",
            "com.samsung.wearable.fit3plugin",
            "com.samsung.android.samsungpass",
            "com.samsung.android.samsungpassautofill",
            "com.samsung.android.spayfw",
            "com.samsung.android.spay",
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.coldwalletservice",
            "com.samsung.android.app.routines",
            "com.samsung.android.app.routineplus"
    ));

    private static final String[] PROTECTED_NAME_TOKENS = new String[]{
            "btg", "nubank", "nu.production", "caixa", "itau", "bradesco", "santander",
            "banrisul", "sicredi", "sicoob", "mercadopago", "picpay", "inter",
            "govbr", "meugov", "geto", "coldwallet", "samsungpass", "spay", "wallet",
            "wearable", "watchmanager", "fit3"
    };

    public static final class SystemState {
        public boolean hasLowHeat;
        public boolean lowHeatActive;

        public boolean hasProtectBattery;
        public int protectBatteryMode;

        public boolean hasAod;
        public boolean aodEnabled;
        public boolean aodTapToShow;
        public boolean aodNewNoti;

        public boolean hasExtraDim;
        public boolean extraDimActive;
        public int extraDimLevel = 38;

        public boolean hasNetworkMode;
        public String networkModeRaw = "";
        public boolean networkHas5gNr;
        public String origNetworkMode = "";

        public boolean hasMcfContinuity;
        public String mcfContinuity = "";
        public String mcfMyDevice = "";
        public String mcfFamilyShare = "";

        public boolean moseyInstalled;
        public boolean moseyDisabled;

        public boolean hasFreezer;
        public String cachedAppsFreezer = "";
        public String kernelFreezerNote = "";

        public float windowScale = 1.0f;
        public float transitionScale = 1.0f;
        public float animatorScale = 1.0f;
        public boolean hasRefreshControl;
        public int refreshRateMode = -1;
        public float minRefreshHz = 0.0f;
        public float peakRefreshHz = 120.0f;
        public int maxHardwareHz = 120;
        public final List<Integer> supportedHzDesc = new ArrayList<Integer>();
        public String privateDnsMode = "";
        public double freeStorageGb = 0.0;
        public int undoCount = 0;
        public String lastArtReport = "";
    }

    public static final class ActionResult {
        public boolean ok;
        public String message;
        public boolean recommendMobileNetworkScreen;

        public ActionResult(boolean ok, String message) {
            this(ok, message, false);
        }

        public ActionResult(boolean ok, String message, boolean recommendMobileNetworkScreen) {
            this.ok = ok;
            this.message = message;
            this.recommendMobileNetworkScreen = recommendMobileNetworkScreen;
        }
    }

    public static final class BatterySafetySnapshot {
        public int level = -1;
        public double tempC = 0.0;
        public boolean plugged = false;
    }

    public static final class UndoEntry {
        public String packageName;
        public String appLabel;
        public String prevRunAnyBg;
        public String prevBucket;
        public String prevDozeMode = "";
        public String appliedSummary;
        public long timestamp;
    }

    public static final class DozeWhitelistEntry {
        public String packageName = "";
        public String appLabel = "";
        public String uid = "";
        public boolean protectedApp = false;
    }

    private SystemTweaksController() {
    }

    public static SystemState readLocalState(Context context) {
        SystemState st = new SystemState();
        ContentResolver cr = context.getContentResolver();
        SharedPreferences prefs = prefs(context);

        String lowHeat = AnalyticsEngine.safeGlobal(cr, "sem_low_heat_mode");
        st.hasLowHeat = lowHeat != null;
        st.lowHeatActive = "1".equals(lowHeat);

        String prot = AnalyticsEngine.safeGlobal(cr, "protect_battery");
        st.hasProtectBattery = prot != null;
        st.protectBatteryMode = parseIntSafe(prot, 0);

        String aod = AnalyticsEngine.safeSystem(cr, "aod_mode");
        st.hasAod = aod != null;
        st.aodEnabled = "1".equals(aod);
        st.aodTapToShow = "1".equals(AnalyticsEngine.safeSystem(cr, "aod_tap_to_show_mode"));
        st.aodNewNoti = "1".equals(AnalyticsEngine.safeSystem(cr, "aod_show_for_new_noti"));

        String extraDim = AnalyticsEngine.safeSecure(cr, "reduce_bright_colors_activated");
        st.hasExtraDim = extraDim != null;
        st.extraDimActive = "1".equals(extraDim);
        st.extraDimLevel = Math.max(5, Math.min(95, parseIntSafe(AnalyticsEngine.safeSecure(cr, "reduce_bright_colors_level"), 38)));

        String net = AnalyticsEngine.safeGlobal(cr, "preferred_network_mode");
        st.hasNetworkMode = net != null && !net.trim().isEmpty();
        st.networkModeRaw = net != null ? net.trim() : "";
        st.networkHas5gNr = containsNrMode(st.networkModeRaw);
        st.origNetworkMode = prefs.getString(KEY_ORIG_NET, "");

        st.mcfContinuity = nonNull(AnalyticsEngine.safeSystem(cr, "mcf_continuity"));
        st.mcfMyDevice = nonNull(AnalyticsEngine.safeSystem(cr, "mcf_mydevice_activated"));
        st.mcfFamilyShare = nonNull(AnalyticsEngine.safeSystem(cr, "mcf_family_device_share_enabled"));
        st.hasMcfContinuity = !st.mcfContinuity.isEmpty() || !st.mcfMyDevice.isEmpty() || !st.mcfFamilyShare.isEmpty();

        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(MOSEY_PACKAGE, 0);
            st.moseyInstalled = true;
            int enabledState = pm.getApplicationEnabledSetting(MOSEY_PACKAGE);
            st.moseyDisabled = enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Throwable ignored) {
            st.moseyInstalled = false;
        }

        String freezer = AnalyticsEngine.safeGlobal(cr, "cached_apps_freezer");
        st.hasFreezer = freezer != null;
        st.cachedAppsFreezer = freezer != null ? freezer : "indisponível";

        st.windowScale = parseFloatSafe(AnalyticsEngine.safeGlobal(cr, "window_animation_scale"), 1.0f);
        st.transitionScale = parseFloatSafe(AnalyticsEngine.safeGlobal(cr, "transition_animation_scale"), 1.0f);
        st.animatorScale = parseFloatSafe(AnalyticsEngine.safeGlobal(cr, "animator_duration_scale"), 1.0f);
        populateRefreshState(context, cr, st);
        st.privateDnsMode = nonNull(AnalyticsEngine.safeGlobal(cr, "private_dns_mode"));

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            st.freeStorageGb = stat.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
        } catch (Throwable ignored) {}

        st.undoCount = loadUndoEntries(context).size();
        st.lastArtReport = prefs.getString(KEY_LAST_ART_REPORT, "");
        return st;
    }

    public static SystemState readWithShizukuDetails(Context context) {
        SystemState st = readLocalState(context);
        if (!ShizukuBridge.hasPermission(context)) {
            return st;
        }
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context,
                "device_config get activity_manager_native_boot use_freezer 2>/dev/null; "
                + "dumpsys activity settings 2>/dev/null | grep -E 'freezer|use_freezer' | head -n 4",
                5000L);
        if (res.isOk() || !res.stdout.isEmpty()) {
            String out = res.stdout.trim();
            if (!out.isEmpty()) {
                st.kernelFreezerNote = out.replace('\n', ' ').trim();
                if (st.kernelFreezerNote.length() > 80) {
                    st.kernelFreezerNote = st.kernelFreezerNote.substring(0, 77) + "...";
                }
            }
        }
        return st;
    }

    public static ActionResult toggleLowHeatMode(Context context) {
        SystemState st = readLocalState(context);
        if (!st.hasLowHeat) return new ActionResult(false, "Perfil Leve (sem_low_heat_mode) não disponível nesta ROM.");
        String target = st.lowHeatActive ? "0" : "1";
        return runSettingsCommand(context,
                "settings put global sem_low_heat_mode " + target,
                "1".equals(target) ? "✅ Perfil Leve ATIVADO" : "⚡ Perfil Leve DESATIVADO (Padrão)");
    }

    public static ActionResult toggleProtectBattery(Context context) {
        SystemState st = readLocalState(context);
        if (!st.hasProtectBattery) return new ActionResult(false, "Proteção de carga não exposta nesta ROM.");
        String target = st.protectBatteryMode > 0 ? "0" : "1";
        return runSettingsCommand(context,
                "settings put global protect_battery " + target,
                "1".equals(target) ? "✅ Proteção de Carga ATIVADA (80%)" : "🔋 Proteção de Carga DESLIGADA (100%)");
    }

    public static ActionResult setAodPreset(Context context, int preset) {
        String cmd;
        String msg;
        if (preset == 0) {
            cmd = "settings put system aod_mode 0";
            msg = "✅ Always-On Display DESLIGADO";
        } else if (preset == 1) {
            cmd = "settings put system aod_mode 1 && settings put system aod_tap_to_show_mode 1 && settings put system aod_show_for_new_noti 0";
            msg = "✅ AOD em 'Toque para exibir' (~18,6 mAh/ciclo)";
        } else {
            cmd = "settings put system aod_mode 1 && settings put system aod_tap_to_show_mode 0";
            msg = "⚠️ AOD definido como Sempre Ativo";
        }
        return runSettingsCommand(context, cmd, msg);
    }

    public static ActionResult toggleExtraDim(Context context) {
        SystemState st = readLocalState(context);
        if (!st.hasExtraDim) return new ActionResult(false, "Brilho Extra Reduzido indisponível neste aparelho.");
        String target = st.extraDimActive ? "0" : "1";
        return runSettingsCommand(context,
                "settings put secure reduce_bright_colors_activated " + target,
                "1".equals(target) ? "✅ Brilho Extra ATIVADO" : "☀️ Brilho Extra DESATIVADO");
    }

    // ---------- Refresh Rate Teto / Piso Adaptativo (0% background) ----------

    private static void populateRefreshState(Context context, ContentResolver cr, SystemState st) {
        Set<Integer> hzSet = new LinkedHashSet<Integer>();
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display disp = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (disp != null) {
                Display.Mode[] modes = disp.getSupportedModes();
                if (modes != null) {
                    for (Display.Mode m : modes) {
                        int r = Math.round(m.getRefreshRate());
                        if (r > 0) hzSet.add(r);
                    }
                }
                float[] rates = disp.getSupportedRefreshRates();
                if (rates != null) {
                    for (float r : rates) {
                        int ir = Math.round(r);
                        if (ir > 0) hzSet.add(ir);
                    }
                }
            }
        } catch (Throwable ignored) {}

        st.supportedHzDesc.clear();
        st.supportedHzDesc.addAll(hzSet);
        Collections.sort(st.supportedHzDesc, Collections.reverseOrder());
        st.maxHardwareHz = st.supportedHzDesc.isEmpty() ? 120 : st.supportedHzDesc.get(0);

        String peakRaw = AnalyticsEngine.safeSystem(cr, "peak_refresh_rate");
        String minRaw = AnalyticsEngine.safeSystem(cr, "min_refresh_rate");
        String modeRaw = AnalyticsEngine.safeSecure(cr, "refresh_rate_mode");
        st.refreshRateMode = parseIntSafe(modeRaw, -1);
        st.minRefreshHz = parseFloatSafe(minRaw, 0.0f);
        st.peakRefreshHz = parseFloatSafe(peakRaw, (float) st.maxHardwareHz);
        st.hasRefreshControl = st.supportedHzDesc.size() > 1 || peakRaw != null || minRaw != null || modeRaw != null;
    }

    public static ActionResult setAdaptiveRefreshBounds(Context context, float minHz, float peakHz) {
        SystemState st = readLocalState(context);
        if (!st.hasRefreshControl) {
            return new ActionResult(false, "Painel/ROM não expõe múltiplos modos de taxa de atualização.");
        }
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(KEY_ORIG_PEAK_HZ)) {
            prefs.edit()
                    .putString(KEY_ORIG_MIN_HZ, String.format(Locale.US, "%.1f", st.minRefreshHz))
                    .putString(KEY_ORIG_PEAK_HZ, String.format(Locale.US, "%.1f", st.peakRefreshHz))
                    .putInt(KEY_ORIG_REFRESH_MODE, st.refreshRateMode)
                    .apply();
        }
        float safePeak = Math.max(48.0f, Math.min((float) st.maxHardwareHz, peakHz));
        float safeMin = Math.max(0.0f, Math.min(safePeak, minHz));
        String minStr = String.format(Locale.US, "%.1f", safeMin);
        String peakStr = String.format(Locale.US, "%.1f", safePeak);

        StringBuilder cmd = new StringBuilder();
        cmd.append("settings put system min_refresh_rate ").append(minStr)
           .append("; settings put system peak_refresh_rate ").append(peakStr);
        if (st.refreshRateMode >= 0) {
            cmd.append("; settings put secure refresh_rate_mode 1");
        }
        String floorLabel = safeMin <= 0.1f ? "Auto/0" : String.format(Locale.US, "%.0f Hz", safeMin);
        return runSettingsCommand(context, cmd.toString(),
                String.format(Locale.US, "⚡ Hz Adaptativo: Piso %s · Teto %.0f Hz", floorLabel, safePeak));
    }

    public static ActionResult restoreDefaultRefreshBounds(Context context) {
        SystemState st = readLocalState(context);
        SharedPreferences prefs = prefs(context);
        String minStr = prefs.getString(KEY_ORIG_MIN_HZ, "0.0");
        String peakStr = prefs.getString(KEY_ORIG_PEAK_HZ, String.format(Locale.US, "%.1f", (float) st.maxHardwareHz));
        int origMode = prefs.getInt(KEY_ORIG_REFRESH_MODE, st.refreshRateMode >= 0 ? 1 : -1);

        StringBuilder cmd = new StringBuilder();
        cmd.append("settings put system min_refresh_rate ").append(minStr)
           .append("; settings put system peak_refresh_rate ").append(peakStr);
        if (origMode >= 0) {
            cmd.append("; settings put secure refresh_rate_mode ").append(origMode);
        }
        return runSettingsCommand(context, cmd.toString(),
                "↩️ Hz restaurado ao padrão (Piso " + minStr + " · Teto " + peakStr + " Hz)");
    }

    public static ActionResult setExtraDimLevel(Context context, int percent) {
        int clamped = Math.max(5, Math.min(95, percent));
        return runSettingsCommand(context,
                "settings put secure reduce_bright_colors_level " + clamped,
                "✅ Intensidade de Brilho Extra ajustada para " + clamped + "%");
    }

    public static ActionResult setNetworkPreferenceMode(Context context, int modeAction) {
        SystemState st = readLocalState(context);
        if (!st.hasNetworkMode) {
            return new ActionResult(false, "Chave preferred_network_mode ausente. Use o atalho Redes Móveis.", true);
        }
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(KEY_ORIG_NET) && !st.networkModeRaw.isEmpty()) {
            prefs.edit().putString(KEY_ORIG_NET, st.networkModeRaw).apply();
        }
        String target;
        String label;
        if (modeAction == 2) {
            String orig = prefs.getString(KEY_ORIG_NET, "");
            if (orig.isEmpty()) return new ActionResult(false, "Nenhum valor original anterior salvo.");
            target = orig;
            label = "↩️ Modo de rede restaurado (" + target + ")";
        } else if (modeAction == 0) {
            target = transformNetworkSlots(st.networkModeRaw, false);
            label = "✅ Preferência gravada para 4G/LTE (" + target + "). Se a barra manter 5G, confirme em Redes Móveis.";
        } else {
            target = transformNetworkSlots(st.networkModeRaw, true);
            label = "📡 Preferência gravada para 5G/Auto (" + target + "). Se não alternar na hora, confirme em Redes Móveis.";
        }
        ActionResult res = runSettingsCommand(context,
                "settings put global preferred_network_mode '" + target + "'",
                label);
        if (res.ok) res.recommendMobileNetworkScreen = true;
        return res;
    }

    private static String transformNetworkSlots(String raw, boolean to5g) {
        String[] parts = raw.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String slot = parts[i].trim();
            if (to5g) {
                if ("9".equals(slot) || "11".equals(slot) || "12".equals(slot)) slot = "26";
                else if ("10".equals(slot)) slot = "27";
                else if ("8".equals(slot)) slot = "25";
                else if ("7".equals(slot)) slot = "24";
            } else {
                if ("26".equals(slot) || "33".equals(slot)) slot = "9";
                else if ("27".equals(slot)) slot = "10";
                else if ("25".equals(slot)) slot = "8";
                else if ("24".equals(slot)) slot = "7";
                else if ("23".equals(slot)) slot = "11";
            }
            if (i > 0) sb.append(",");
            sb.append(slot);
        }
        return sb.toString();
    }

    private static boolean containsNrMode(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        for (String p : raw.split(",")) {
            int val = parseIntSafe(p.trim(), -1);
            if (val >= 23 && val <= 33) return true;
        }
        return false;
    }

    public static ActionResult toggleMcfContinuity(Context context) {
        SystemState st = readLocalState(context);
        if (!st.hasMcfContinuity) {
            return new ActionResult(false, "Serviços Samsung de Continuidade (MCF) não presentes nesta ROM.");
        }
        SharedPreferences prefs = prefs(context);
        boolean currentlyOn = "1".equals(st.mcfContinuity) || "1".equals(st.mcfMyDevice)
                || (!st.mcfFamilyShare.isEmpty() && !"0".equals(st.mcfFamilyShare));

        if (currentlyOn) {
            SharedPreferences.Editor ed = prefs.edit();
            if (!prefs.contains(KEY_ORIG_MCF_CONT) && !st.mcfContinuity.isEmpty()) ed.putString(KEY_ORIG_MCF_CONT, st.mcfContinuity);
            if (!prefs.contains(KEY_ORIG_MCF_MYDEV) && !st.mcfMyDevice.isEmpty()) ed.putString(KEY_ORIG_MCF_MYDEV, st.mcfMyDevice);
            if (!prefs.contains(KEY_ORIG_MCF_FAM) && !st.mcfFamilyShare.isEmpty()) ed.putString(KEY_ORIG_MCF_FAM, st.mcfFamilyShare);
            ed.apply();

            StringBuilder cmd = new StringBuilder();
            if (!st.mcfContinuity.isEmpty()) cmd.append("settings put system mcf_continuity 0; ");
            if (!st.mcfMyDevice.isEmpty()) cmd.append("settings put system mcf_mydevice_activated 0; ");
            if (!st.mcfFamilyShare.isEmpty()) cmd.append("settings put system mcf_family_device_share_enabled 0; ");
            return runSettingsCommand(context, cmd.toString().trim(),
                    "✅ Continuidade Samsung (MCF / Multicontrole ocioso) DESATIVADA (reversível)");
        } else {
            String c = prefs.getString(KEY_ORIG_MCF_CONT, st.mcfContinuity.isEmpty() ? "" : "1");
            String m = prefs.getString(KEY_ORIG_MCF_MYDEV, st.mcfMyDevice.isEmpty() ? "" : "1");
            String f = prefs.getString(KEY_ORIG_MCF_FAM, st.mcfFamilyShare.isEmpty() ? "" : "2");
            StringBuilder cmd = new StringBuilder();
            if (!c.isEmpty()) cmd.append("settings put system mcf_continuity ").append(c).append("; ");
            if (!m.isEmpty()) cmd.append("settings put system mcf_mydevice_activated ").append(m).append("; ");
            if (!f.isEmpty()) cmd.append("settings put system mcf_family_device_share_enabled ").append(f).append("; ");
            return runSettingsCommand(context, cmd.toString().trim(),
                    "↩️ Continuidade Samsung (MCF) RESTAURADA");
        }
    }

    public static ActionResult controlMosey(Context context, int action) {
        SystemState st = readLocalState(context);
        if (!st.moseyInstalled) return new ActionResult(false, "Mosey (com.google.android.mosey) não está instalado.");
        if (action == 0) {
            return runSettingsCommand(context,
                    "pm disable-user --user 0 " + MOSEY_PACKAGE + " && am force-stop " + MOSEY_PACKAGE,
                    "⏸️ Mosey / AirDrop pausado sob demanda (sem escaneamento ocioso)");
        } else if (action == 1) {
            return runSettingsCommand(context,
                    "pm enable " + MOSEY_PACKAGE,
                    "▶️ Mosey / AirDrop reativado");
        } else {
            return runSettingsCommand(context,
                    "am force-stop " + MOSEY_PACKAGE,
                    "⏹️ Processo do Mosey encerrado no momento");
        }
    }

    public static ActionResult toggleCachedAppsFreezer(Context context) {
        SystemState st = readLocalState(context);
        if (!st.hasFreezer) return new ActionResult(false, "Chave cached_apps_freezer ausente neste Android.");
        String next = "enabled".equals(st.cachedAppsFreezer) ? "device_default" : "enabled";
        return runSettingsCommand(context,
                "settings put global cached_apps_freezer " + next,
                "enabled".equals(next)
                        ? "❄️ Freezer de apps em cache forçado para ENABLED"
                        : "↩️ Freezer de apps retornado para DEVICE_DEFAULT");
    }

    public static ActionResult setAnimationScales(Context context, float windowScale, float transitionScale, float animatorScale) {
        String w = formatScale(windowScale);
        String t = formatScale(transitionScale);
        String a = formatScale(animatorScale);
        String cmd = "settings put global window_animation_scale " + w
                + "; settings put global transition_animation_scale " + t
                + "; settings put global animator_duration_scale " + a;
        return runSettingsCommand(context, cmd,
                "🎞️ Animações: Janela " + w + "x | Trans. " + t + "x | Animator " + a + "x");
    }

    public static ActionResult setSingleAnimationScale(Context context, String globalKey, String label, float scale) {
        String val = formatScale(scale);
        return runSettingsCommand(context,
                "settings put global " + globalKey + " " + val,
                "🎞️ " + label + " ajustada para " + val + "x");
    }

    private static String formatScale(float scale) {
        float clamped = Math.max(0.0f, Math.min(1.0f, Math.round(scale * 10f) / 10f));
        return String.format(Locale.US, "%.1f", clamped);
    }

    public static ActionResult trimAppCaches(Context context) {
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        long beforeBytes = getAvailableDataBytes();
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "pm trim-caches 4294967296", 20000L);
        if (!res.isOk()) {
            return new ActionResult(false, "Falha em pm trim-caches: " + pickError(res));
        }
        try { Thread.sleep(450L); } catch (InterruptedException ignored) {}
        long afterBytes = getAvailableDataBytes();
        long deltaMb = Math.max(0L, (afterBytes - beforeBytes) / (1024L * 1024L));
        double freeGb = afterBytes / (1024.0 * 1024.0 * 1024.0);
        return new ActionResult(true,
                String.format(Locale.US, "🧹 Caches temporários limpos! +%d MB recuperados (UFS livre: %.2f GB)", deltaMb, freeGb));
    }

    public static BatterySafetySnapshot readBatterySafety(Context context) {
        BatterySafetySnapshot snap = new BatterySafetySnapshot();
        Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) snap.level = Math.round((level * 100f) / scale);
            snap.tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            snap.plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        return snap;
    }

    public static List<String> getTopRecentPackages(Context context, int limit) {
        List<String> out = new ArrayList<String>();
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY,
                        now - 7L * 24L * 3600L * 1000L, now);
                if (stats != null && !stats.isEmpty()) {
                    Map<String, Long> totals = new LinkedHashMap<String, Long>();
                    for (UsageStats u : stats) {
                        if (u == null || u.getPackageName() == null) continue;
                        long prev = totals.containsKey(u.getPackageName()) ? totals.get(u.getPackageName()) : 0L;
                        totals.put(u.getPackageName(), prev + u.getTotalTimeInForeground());
                    }
                    List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(totals.entrySet());
                    Collections.sort(sorted, new Comparator<Map.Entry<String, Long>>() {
                        @Override
                        public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                            return Long.compare(b.getValue(), a.getValue());
                        }
                    });
                    for (Map.Entry<String, Long> e : sorted) {
                        String pkg = e.getKey();
                        if (pkg.equals(self) || e.getValue() <= 60_000L) continue;
                        if (pm.getLaunchIntentForPackage(pkg) == null) continue;
                        out.add(pkg);
                        if (out.size() >= limit) break;
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (out.isEmpty()) {
            try {
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo ai : apps) {
                    if (ai == null || ai.packageName.equals(self)) continue;
                    boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (isSystem) continue;
                    if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                    out.add(ai.packageName);
                    if (out.size() >= limit) break;
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    public static ActionResult compileTopAppsSpeedProfile(Context context) {
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        BatterySafetySnapshot bat = readBatterySafety(context);
        if (bat.level >= 0 && bat.level < 20 && !bat.plugged) {
            return new ActionResult(false, "Bloqueado por segurança: bateria em " + bat.level + "% fora do carregador.");
        }
        if (bat.tempC >= 39.0) {
            return new ActionResult(false, String.format(Locale.US, "Bloqueado por temperatura: %.1f °C (>= 39 °C).", bat.tempC));
        }

        List<String> targets = getTopRecentPackages(context, 10);
        if (targets.isEmpty()) {
            return new ActionResult(false, "Nenhum app elegível encontrado (libere Acesso ao Uso se necessário).");
        }

        PackageManager pm = context.getPackageManager();
        int compiled = 0;
        int alreadyOptimal = 0;
        int skipped = 0;
        StringBuilder details = new StringBuilder();
        Pattern statusPat = Pattern.compile("\\[status=([^\\]]+)\\]");

        for (String pkg : targets) {
            String label = getPackageLabel(pm, pkg);
            ShizukuBridge.ExecResult inspect = ShizukuBridge.exec(context,
                    "dumpsys package " + pkg + " | grep -m1 '\\[status=' || true", 5000L);
            String curStatus = "";
            Matcher m = statusPat.matcher(inspect.stdout);
            if (m.find()) curStatus = m.group(1);

            if ("speed-profile".equals(curStatus) || "speed".equals(curStatus)) {
                alreadyOptimal++;
                continue;
            }

            ShizukuBridge.ExecResult comp = ShizukuBridge.exec(context,
                    "cmd package compile -m speed-profile " + pkg, 35000L);
            ShizukuBridge.ExecResult inspectAfter = ShizukuBridge.exec(context,
                    "dumpsys package " + pkg + " | grep -m1 '\\[status=' || true", 5000L);
            String afterStatus = "";
            Matcher ma = statusPat.matcher(inspectAfter.stdout);
            if (ma.find()) afterStatus = ma.group(1);

            if (comp.isOk() && ("speed-profile".equals(afterStatus) || "speed".equals(afterStatus))) {
                compiled++;
                details.append("\n✅ ").append(label).append(": ")
                        .append(curStatus.isEmpty() ? "?" : curStatus).append(" → ").append(afterStatus);
            } else {
                skipped++;
                details.append("\n• ").append(label).append(": mantido em ")
                        .append(afterStatus.isEmpty() ? (curStatus.isEmpty() ? "verify" : curStatus) : afterStatus);
            }
        }

        String summary = String.format(Locale.US,
                "ART speed-profile: %d compilado(s), %d já otimizado(s), %d aguardando perfil de uso.%s",
                compiled, alreadyOptimal, skipped, details.toString());
        prefs(context).edit().putString(KEY_LAST_ART_REPORT,
                String.format(Locale.US, "%d comp. · %d já OK · %d s/ perfil", compiled, alreadyOptimal, skipped)).apply();
        return new ActionResult(true, summary);
    }

    public static boolean isProtectedPackage(Context context, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return true;
        String clean = pkg.trim();
        if (clean.equals(context.getPackageName())) return true;
        if (EXACT_PROTECTED_PACKAGES.contains(clean)) return true;

        String low = clean.toLowerCase(Locale.US);
        for (String tok : PROTECTED_NAME_TOKENS) {
            if (low.contains(tok)) return true;
        }

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(clean, 0);
            if (ai.uid < Process.FIRST_APPLICATION_UID) return true;
        } catch (Throwable ignored) {
            return true;
        }

        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                    if (clean.equals(imi.getPackageName())) return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = context.getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && clean.equals(ri.activityInfo.packageName)) {
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static ActionResult applyAppControlAction(Context context, String pkg, String label, int mode) {
        if (isProtectedPackage(context, pkg)) {
            return new ActionResult(false, "🔒 App protegido (" + label + "). Ação bloqueada por segurança.");
        }
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        if (mode == 4) {
            return undoSinglePackage(context, pkg);
        }

        if (mode == 0) {
            return runSettingsCommand(context, "am force-stop " + pkg,
                    "🛑 Force-stop enviado para " + label);
        }

        BgStateSnapshot snap = probeAppBackgroundState(context, pkg, true);
        String prevOp = snap.op;
        String prevBucket = snap.bucket;
        String prevDoze = snap.doze;

        StringBuilder cmd = new StringBuilder();
        String desc;
        if (mode == 1) {
            cmd.append("cmd appops set ").append(pkg).append(" RUN_ANY_IN_BACKGROUND ignore");
            desc = "appops ignore";
            prevDoze = "";
        } else if (mode == 2) {
            cmd.append("am set-standby-bucket ").append(pkg).append(" restricted");
            desc = "bucket restricted";
            prevDoze = "";
        } else if (mode == 5) {
            if ("user".equals(prevDoze)) {
                cmd.append("dumpsys deviceidle whitelist -").append(pkg);
                desc = "removido da whitelist Doze";
            } else if ("system".equals(prevDoze)) {
                cmd.append("cmd deviceidle sys-whitelist -").append(pkg);
                desc = "removido da sys-whitelist Doze";
            } else {
                return new ActionResult(true, "ℹ️ " + label + " já não estava na whitelist do Doze.");
            }
        } else {
            cmd.append("cmd appops set ").append(pkg).append(" RUN_ANY_IN_BACKGROUND ignore && ")
               .append("am set-standby-bucket ").append(pkg).append(" restricted");
            if ("user".equals(prevDoze)) {
                cmd.append(" && dumpsys deviceidle whitelist -").append(pkg);
                desc = "appops ignore + restricted + Doze off + force-stop";
            } else {
                prevDoze = "";
                desc = "appops ignore + restricted + force-stop";
            }
            cmd.append(" && am force-stop ").append(pkg);
        }

        ShizukuBridge.ExecResult execRes = ShizukuBridge.exec(context, cmd.toString(), 8000L);
        if (!execRes.isOk()) {
            return new ActionResult(false, "Falha ao aplicar em " + label + ": " + pickError(execRes));
        }

        recordUndoEntry(context, pkg, label, prevOp, prevBucket, prevDoze, desc);
        return new ActionResult(true, "✅ " + label + ": " + desc + " (salvo no histórico p/ desfazer)");
    }

    public static List<DozeWhitelistEntry> inspectUserDozeWhitelist(Context context) {
        String dump = DumpHelper.dumpServiceText("deviceidle");
        if ((dump == null || !dump.contains("user,")) && ensureShizukuReady(context)) {
            ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, "dumpsys deviceidle whitelist", 6000L);
            if (res.stdout != null && !res.stdout.isEmpty()) dump = res.stdout;
        }
        List<DozeWhitelistEntry> list = new ArrayList<DozeWhitelistEntry>();
        if (dump == null || dump.isEmpty()) return list;

        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<String>();
        for (String line : dump.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("user,")) continue;
            String[] parts = trimmed.split(",");
            if (parts.length < 2) continue;
            String pkg = parts[1].trim();
            if (pkg.isEmpty() || !seen.add(pkg)) continue;
            DozeWhitelistEntry entry = new DozeWhitelistEntry();
            entry.packageName = pkg;
            entry.uid = parts.length >= 3 ? parts[2].trim() : "";
            entry.appLabel = getPackageLabel(pm, pkg);
            entry.protectedApp = isProtectedPackage(context, pkg);
            list.add(entry);
        }
        Collections.sort(list, new Comparator<DozeWhitelistEntry>() {
            @Override
            public int compare(DozeWhitelistEntry a, DozeWhitelistEntry b) {
                if (a.protectedApp != b.protectedApp) return a.protectedApp ? 1 : -1;
                return a.appLabel.compareToIgnoreCase(b.appLabel);
            }
        });
        return list;
    }

    private static final class BgStateSnapshot {
        String op = "allow";
        String bucket = "active";
        String doze = "";
    }

    private static BgStateSnapshot probeAppBackgroundState(Context context, String pkg, boolean includeDoze) {
        BgStateSnapshot snap = new BgStateSnapshot();
        String cmd = "OP=$(cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -oE 'allow|ignore|deny|default' | head -n 1); "
                + "BK=$(am get-standby-bucket " + pkg + " 2>/dev/null | tr -d '\\r\\n'); ";
        if (includeDoze) {
            cmd += "DZ=$(dumpsys deviceidle whitelist 2>/dev/null | grep -F -m1 '," + pkg + ",' || true); ";
        } else {
            cmd += "DZ=''; ";
        }
        cmd += "echo \"OP=${OP:-allow}|BK=${BK:-10}|DZ=${DZ}\"";
        ShizukuBridge.ExecResult probe = ShizukuBridge.exec(context, cmd, 6000L);
        Matcher mOp = Pattern.compile("OP=([^|\\r\\n]+)").matcher(probe.stdout);
        if (mOp.find()) snap.op = mOp.group(1).trim();
        Matcher mBk = Pattern.compile("BK=([^|\\r\\n]+)").matcher(probe.stdout);
        if (mBk.find()) snap.bucket = mapBucketName(mBk.group(1).trim());
        Matcher mDz = Pattern.compile("DZ=([^\\r\\n]+)").matcher(probe.stdout);
        if (mDz.find()) {
            String dzLine = mDz.group(1).trim();
            if (dzLine.startsWith("user,")) snap.doze = "user";
            else if (dzLine.startsWith("system,")) snap.doze = "system";
        }
        return snap;
    }

    public static ActionResult removePackagesFromDozeWhitelist(Context context, List<DozeWhitelistEntry> selected) {
        if (selected == null || selected.isEmpty()) {
            return new ActionResult(true, "Nenhum app selecionado para remover da whitelist Doze.");
        }
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        int removed = 0;
        for (DozeWhitelistEntry e : selected) {
            if (e.protectedApp || isProtectedPackage(context, e.packageName)) continue;
            BgStateSnapshot snap = probeAppBackgroundState(context, e.packageName, false);
            ShizukuBridge.ExecResult res = ShizukuBridge.exec(context,
                    "dumpsys deviceidle whitelist -" + e.packageName, 6000L);
            if (res.isOk()) {
                removed++;
                recordUndoEntry(context, e.packageName, e.appLabel, snap.op, snap.bucket, "user", "removido whitelist Doze");
            }
        }
        return new ActionResult(removed > 0, removed > 0
                ? "💤 " + removed + " app(s) removido(s) da whitelist Doze (salvos p/ desfazer)!"
                : "Nenhum app pôde ser removido da whitelist Doze.");
    }

    public static List<UndoEntry> loadUndoEntries(Context context) {
        List<UndoEntry> list = new ArrayList<UndoEntry>();
        try {
            String raw = prefs(context).getString(KEY_APP_UNDO_LIST, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                UndoEntry e = new UndoEntry();
                e.packageName = o.optString("pkg", "");
                e.appLabel = o.optString("label", e.packageName);
                e.prevRunAnyBg = o.optString("prevOp", "allow");
                e.prevBucket = o.optString("prevBucket", "active");
                e.prevDozeMode = o.optString("prevDoze", "");
                e.appliedSummary = o.optString("applied", "");
                e.timestamp = o.optLong("ts", 0L);
                if (!e.packageName.isEmpty()) list.add(e);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static ActionResult undoSinglePackage(Context context, String pkg) {
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        List<UndoEntry> entries = loadUndoEntries(context);
        UndoEntry match = null;
        for (UndoEntry e : entries) {
            if (pkg.equals(e.packageName)) {
                match = e;
                break;
            }
        }
        String op = match != null ? match.prevRunAnyBg : "allow";
        String bk = match != null ? match.prevBucket : "active";
        String dz = match != null ? match.prevDozeMode : "";
        String cmd = "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND " + op
                + " && am set-standby-bucket " + pkg + " " + bk
                + buildDozeRestoreSuffix(pkg, dz);
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, cmd, 8000L);
        if (!res.isOk()) {
            return new ActionResult(false, "Falha ao restaurar " + pkg + ": " + pickError(res));
        }
        List<UndoEntry> kept = new ArrayList<UndoEntry>();
        for (UndoEntry e : entries) {
            if (!pkg.equals(e.packageName)) kept.add(e);
        }
        saveUndoEntries(context, kept);
        return new ActionResult(true, "↩️ " + (match != null ? match.appLabel : pkg) + " restaurado (" + op + " / " + bk + ")");
    }

    public static ActionResult undoAllRestrictions(Context context) {
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        List<UndoEntry> entries = loadUndoEntries(context);
        if (entries.isEmpty()) {
            return new ActionResult(true, "Nenhuma restrição pendente para desfazer.");
        }
        int okCount = 0;
        List<UndoEntry> remaining = new ArrayList<UndoEntry>();
        for (UndoEntry e : entries) {
            String cmd = "cmd appops set " + e.packageName + " RUN_ANY_IN_BACKGROUND " + e.prevRunAnyBg
                    + " && am set-standby-bucket " + e.packageName + " " + e.prevBucket
                    + buildDozeRestoreSuffix(e.packageName, e.prevDozeMode);
            ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, cmd, 7000L);
            if (res.isOk()) okCount++;
            else remaining.add(e);
        }
        saveUndoEntries(context, remaining);
        if (remaining.isEmpty()) {
            return new ActionResult(true, "↩️ " + okCount + " app(s) restaurado(s) ao estado exato anterior!");
        }
        return new ActionResult(false, String.format(Locale.US,
                "Restaurados %d; falharam %d (talvez desinstalados).", okCount, remaining.size()));
    }

    private static String buildDozeRestoreSuffix(String pkg, String prevDozeMode) {
        if ("user".equals(prevDozeMode)) {
            return " && dumpsys deviceidle whitelist +" + pkg;
        }
        if ("system".equals(prevDozeMode)) {
            return " && cmd deviceidle sys-whitelist +" + pkg;
        }
        return "";
    }

    private static void recordUndoEntry(Context context, String pkg, String label, String prevOp, String prevBucket, String prevDoze, String desc) {
        List<UndoEntry> entries = loadUndoEntries(context);
        for (UndoEntry existing : entries) {
            if (pkg.equals(existing.packageName)) {
                if ((existing.prevDozeMode == null || existing.prevDozeMode.isEmpty()) && prevDoze != null && !prevDoze.isEmpty()) {
                    existing.prevDozeMode = prevDoze;
                }
                existing.appliedSummary = desc;
                existing.timestamp = System.currentTimeMillis();
                saveUndoEntries(context, entries);
                return;
            }
        }
        UndoEntry e = new UndoEntry();
        e.packageName = pkg;
        e.appLabel = label;
        e.prevRunAnyBg = prevOp;
        e.prevBucket = prevBucket;
        e.prevDozeMode = prevDoze != null ? prevDoze : "";
        e.appliedSummary = desc;
        e.timestamp = System.currentTimeMillis();
        entries.add(e);
        saveUndoEntries(context, entries);
    }

    private static void saveUndoEntries(Context context, List<UndoEntry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (UndoEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.packageName);
                o.put("label", e.appLabel);
                o.put("prevOp", e.prevRunAnyBg);
                o.put("prevBucket", e.prevBucket);
                o.put("prevDoze", e.prevDozeMode != null ? e.prevDozeMode : "");
                o.put("applied", e.appliedSummary);
                o.put("ts", e.timestamp);
                arr.put(o);
            }
            prefs(context).edit().putString(KEY_APP_UNDO_LIST, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static String mapBucketName(String raw) {
        int val = parseIntSafe(raw, -1);
        if (val < 0) {
            String low = raw.toLowerCase(Locale.US);
            if ("active".equals(low) || "working_set".equals(low) || "frequent".equals(low)
                    || "rare".equals(low) || "restricted".equals(low)) {
                return low;
            }
            return "active";
        }
        if (val <= 10) return "active";
        if (val <= 20) return "working_set";
        if (val <= 30) return "frequent";
        if (val <= 40) return "rare";
        return "restricted";
    }

    private static ActionResult runSettingsCommand(Context context, String cmd, String successMsg) {
        if (!ensureShizukuReady(context)) {
            return new ActionResult(false, "Ative/autorize o Shizuku primeiro na aba OTIM.");
        }
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, cmd, 10000L);
        if (res.isOk()) {
            return new ActionResult(true, successMsg);
        }
        return new ActionResult(false, "Erro Shizuku: " + pickError(res));
    }

    private static boolean ensureShizukuReady(Context context) {
        return ShizukuBridge.ensureAttached(context) && ShizukuBridge.hasPermission(context);
    }

    private static String pickError(ShizukuBridge.ExecResult res) {
        if (res.error != null && !res.error.isEmpty()) return res.error;
        if (!res.stderr.isEmpty()) return res.stderr;
        return "exit=" + res.exitCode;
    }

    private static long getAvailableDataBytes() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            return stat.getAvailableBytes();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String getPackageLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null) return l.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String nonNull(String s) {
        return s != null ? s.trim() : "";
    }

    private static int parseIntSafe(String raw, int def) {
        try { return Integer.parseInt(raw.trim()); } catch (Throwable t) { return def; }
    }

    private static float parseFloatSafe(String raw, float def) {
        try { return Float.parseFloat(raw.trim()); } catch (Throwable t) { return def; }
    }
}

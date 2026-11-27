package com.omnia.optimizer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Audits hardware & S23 Tweaks signals inspired by s23-ultra-tweaks v1.3.9
 * without altering system state unless explicitly requested by the user.
 */
public class TweaksAudit {

    private static volatile FloatingFeatures cachedFeatures;

    private static class FloatingFeatures {
        boolean aodFullscreen;
        boolean bsohSettings;
        boolean autoPowerOnOff;
        boolean voipLouder;
    }

    public static class TweaksStatus {
        public boolean secAuthPresent;
        public boolean fullScreenAodOverlay;
        public boolean fullScreenAodFeature;
        public boolean bsohSettingsFeature;
        public boolean autoPowerOnOffFeature;
        public boolean aodEnabled;
        public boolean aodTapToShow;
        public boolean moseyInstalled;
        public boolean ksuManagerInstalled;
        public ShizukuBridge.ChemicalBatteryCache chemHealth;
        public boolean fullScreenAodContinuousWarning;
        public String summaryLines = "";
        public String causalWarning = "";
    }

    public static TweaksStatus inspect(Context context) {
        TweaksStatus st = new TweaksStatus();
        PackageManager pm = context.getPackageManager();
        ContentResolver cr = context.getContentResolver();
        FloatingFeatures ff = readFloatingFeatures();

        st.secAuthPresent = new File("/sys/class/power_supply/sec_auth").exists();
        st.moseyInstalled = isPackageInstalled(pm, "com.google.android.mosey");
        st.ksuManagerInstalled = isPackageInstalled(pm, "me.weishu.kernelsu");
        st.chemHealth = ShizukuBridge.loadCachedChemicalHealth(context);

        String aodMode = AnalyticsEngine.safeSystem(cr, "aod_mode");
        String aodTap = AnalyticsEngine.safeSystem(cr, "aod_tap_to_show_mode");
        st.aodEnabled = "1".equals(aodMode);
        st.aodTapToShow = "1".equals(aodTap);

        String overlayCache = ShizukuBridge.getCachedOverlayList(context);
        st.fullScreenAodOverlay = overlayCache.contains("s23tweaks-aodbright")
                || overlayCache.contains("aodbright");
        st.fullScreenAodFeature = ff.aodFullscreen;
        st.bsohSettingsFeature = ff.bsohSettings;
        st.autoPowerOnOffFeature = ff.autoPowerOnOff;
        boolean hasFullScreenAod = st.fullScreenAodOverlay || st.fullScreenAodFeature;

        // Verified S23 checkup insight: Full-Screen AOD continuous consumes 116-312 mAh vs ~18.6 mAh with Tap to Show.
        st.fullScreenAodContinuousWarning = st.aodEnabled && !st.aodTapToShow;

        StringBuilder sb = new StringBuilder();
        sb.append(st.secAuthPresent || st.bsohSettingsFeature
                ? "✅ Saúde Química (sec_auth / BSOH): Suportado pelo kernel/ROM\n"
                : "ℹ️ Saúde Química (sec_auth): Nó Samsung não exposto neste aparelho\n");

        if (st.chemHealth != null && st.chemHealth.hasUsefulData()) {
            sb.append("🔋 Saúde Química (via Shizuku): ");
            boolean sep = false;
            if (st.chemHealth.asoc > 0) {
                sb.append(String.format(Locale.US, "ASOC %.0f%%", st.chemHealth.asoc));
                sep = true;
            }
            if (st.chemHealth.cycles > 0) {
                if (sep) sb.append(" · ");
                sb.append(String.format(Locale.US, "%.1f ciclos", st.chemHealth.cycles));
                sep = true;
            }
            if (st.chemHealth.bsoh > 0) {
                if (sep) sb.append(" · ");
                sb.append(String.format(Locale.US, "BSOH %.0f%%", st.chemHealth.bsoh));
                sep = true;
            }
            if (!st.chemHealth.firstUseDate.isEmpty()) {
                if (sep) sb.append(" · ");
                sb.append("1º uso ").append(formatFirstUseDate(st.chemHealth.firstUseDate));
            }
            sb.append("\n");
        } else {
            sb.append("ℹ️ Saúde Química (BSOH/1º uso): Toque 'Shizuku Auto-Ativar' p/ atualizar\n");
        }

        if (hasFullScreenAod) {
            sb.append(st.aodTapToShow
                    ? "✅ Full-Screen AOD + Toque p/ Exibir: Econômico (~18,6 mAh/ciclo)\n"
                    : "⚠️ Full-Screen AOD contínuo: alto impacto (116–312 mAh vs 18,6 mAh c/ Toque p/ Exibir!)\n");
        } else if (st.aodEnabled) {
            sb.append(st.aodTapToShow
                    ? "✅ AOD Toque p/ Exibir: Modo econômico ativo\n"
                    : "⚠️ AOD Contínuo: Ative 'Toque para exibir' para poupar tela OLED\n");
        }

        if (st.autoPowerOnOffFeature) {
            sb.append("✅ Auto Power On/Off (ROM): Suporte ativo\n");
        }
        if (st.moseyInstalled) {
            sb.append("ℹ️ AirDrop / Mosey: Instalado (mantenha desligado se notar BLE/UWB ocioso)\n");
        }
        if (st.ksuManagerInstalled) {
            sb.append("ℹ️ KernelSU / HideKSU: Após reboot, confirme módulos ativos antes de abrir apps de banco\n");
        }

        if (st.fullScreenAodContinuousWarning) {
            st.causalWarning = (hasFullScreenAod ? "🌙 FULL-SCREEN AOD CONTÍNUO: " : "🌙 AOD CONTÍNUO: ")
                    + "Sem 'Toque para exibir', o painel OLED consome até 6–15x mais em repouso "
                    + "(medição verificada em Galaxy Ultra: 116–312 mAh contínuo vs 18,6 mAh no Toque).\n\n";
        }

        st.summaryLines = sb.toString().trim();
        return st;
    }

    private static FloatingFeatures readFloatingFeatures() {
        FloatingFeatures cached = cachedFeatures;
        if (cached != null) return cached;
        FloatingFeatures ff = new FloatingFeatures();
        File f = new File("/system/etc/floating_feature.xml");
        if (f.exists() && f.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("SEC_FLOATING_FEATURE_LCD_CONFIG_AOD_FULLSCREEN") && line.contains(">1<")) {
                        ff.aodFullscreen = true;
                    } else if (line.contains("SEC_FLOATING_FEATURE_BATTERY_SUPPORT_BSOH_SETTINGS") && line.contains("TRUE")) {
                        ff.bsohSettings = true;
                    } else if (line.contains("SEC_FLOATING_FEATURE_SETTINGS_SUPPORT_AUTO_POWER_ON_OFF") && line.contains("TRUE")) {
                        ff.autoPowerOnOff = true;
                    } else if (line.contains("SEC_FLOATING_FEATURE_AUDIO_SUPPORT_VOIP_SOUND_LOUDER") && line.contains("TRUE")) {
                        ff.voipLouder = true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        cachedFeatures = ff;
        return ff;
    }

    public static ShizukuBridge.ExecResult openWifi7Labs(Context context) {
        return ShizukuBridge.exec(context,
                "am start -n com.android.settings/.SubSettings " +
                "--es :settings:show_fragment com.samsung.android.settings.wifi.develop.WifiLabs7ModeSettingsFragment",
                6000L);
    }

    public static String formatFirstUseDate(String raw) {
        if (raw == null || raw.length() != 8) return raw != null ? raw : "-";
        return raw.substring(6, 8) + "/" + raw.substring(4, 6) + "/" + raw.substring(0, 4);
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

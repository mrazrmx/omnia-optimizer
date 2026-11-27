package com.omnia.optimizer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Console ADB local sob demanda inspirado no aShell You (0% em segundo plano).
 * Executa via ShizukuBinder IPC sem daemon, suporta cd persistente, cancelamento e exportação .txt.
 */
public final class LocalShellController {

    private static final String PREFS = "omnia_local_shell";
    private static final String KEY_HISTORY = "cmd_history";
    private static final String KEY_CWD = "current_dir";
    private static final int MAX_HISTORY = 16;

    public static final String[] QUICK_SNIPPETS = new String[]{
            "dumpsys battery",
            "dumpsys deviceidle whitelist",
            "settings get global debug.hwui.renderer",
            "cmd package list packages -3",
            "dumpsys gfxinfo | head -n 35",
            "getprop ro.build.version.release",
            "uptime"
    };

    private LocalShellController() {
    }

    public static String normalizeCommand(String raw) {
        if (raw == null) return "";
        String cmd = raw.trim();
        String lower = cmd.toLowerCase(Locale.US);
        if (lower.startsWith("adb shell ")) {
            cmd = cmd.substring("adb shell ".length()).trim();
        } else if ("adb shell".equals(lower)) {
            cmd = "";
        }
        return cmd;
    }

    public static String getWorkingDir(Context context) {
        String dir = prefs(context).getString(KEY_CWD, "/storage/emulated/0");
        return (dir == null || dir.trim().isEmpty()) ? "/storage/emulated/0" : dir.trim();
    }

    public static void setWorkingDir(Context context, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        prefs(context).edit().putString(KEY_CWD, dir.trim()).apply();
    }

    public static boolean isClearCommand(String cmd) {
        return "clear".equalsIgnoreCase(cmd) || "cls".equalsIgnoreCase(cmd);
    }

    /**
     * Resolves standalone `cd <path>` and returns message, or null when command should run normally.
     */
    public static CdOutcome tryHandleDirectoryChange(Context context, String cmd) {
        if (!"cd".equals(cmd) && !cmd.startsWith("cd ")) {
            return null;
        }
        if (cmd.contains("&&") || cmd.contains(";")) {
            return null;
        }
        String target = cmd.length() > 2 ? cmd.substring(2).trim() : "/storage/emulated/0";
        if (target.isEmpty() || "~".equals(target)) {
            target = "/storage/emulated/0";
        }
        String current = getWorkingDir(context);
        String probe = "cd " + shellQuote(current) + " 2>/dev/null; cd " + shellQuote(target) + " && pwd";
        ShizukuBridge.ExecResult res = ShizukuBridge.exec(context, probe, 5000L);
        if (res.isOk() && res.stdout != null && !res.stdout.trim().isEmpty()) {
            String resolved = res.stdout.trim().split("\\r?\\n")[0].trim();
            setWorkingDir(context, resolved);
            return new CdOutcome(true, resolved, "Diretório atual: " + resolved);
        }
        String err = (res.stderr != null && !res.stderr.trim().isEmpty()) ? res.stderr.trim() : "Diretório inacessível: " + target;
        return new CdOutcome(false, current, err);
    }

    public static String wrapWithWorkingDir(Context context, String cmd) {
        String dir = getWorkingDir(context);
        if ("/".equals(dir)) {
            return cmd;
        }
        return "cd " + shellQuote(dir) + " 2>/dev/null; " + cmd;
    }

    public static final class CdOutcome {
        public final boolean ok;
        public final String dir;
        public final String message;

        public CdOutcome(boolean ok, String dir, String message) {
            this.ok = ok;
            this.dir = dir;
            this.message = message;
        }
    }

    public static List<String> getHistory(Context context) {
        List<String> list = new ArrayList<String>();
        try {
            String raw = prefs(context).getString(KEY_HISTORY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i, "").trim();
                if (!item.isEmpty()) list.add(item);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public static void recordHistory(Context context, String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        String clean = cmd.trim();
        List<String> list = getHistory(context);
        list.remove(clean);
        list.add(0, clean);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (String item : list) {
            arr.put(item);
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    public static String saveOutputToFile(Context context, String text) {
        try {
            File baseDir = new File(StorageManager.BASE_DIR_PATH, "ShellLogs");
            baseDir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File outFile = new File(baseDir, "shell-" + stamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shellQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\"'\"'")) + "'";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

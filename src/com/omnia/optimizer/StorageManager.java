package com.omnia.optimizer;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class StorageManager {
    private static final String TAG = "OmniaStorage";
    public static final String BASE_DIR_PATH = "/sdcard/Documents/OmniaOptimizer";
    public static final String CHECKLIST_FILE_NAME = "otimizacao_checklist.json";

    public static class RestoreResult {
        public boolean success;
        public int addedCycles;
        public int updatedCycles;
        public int restoredFiles;
        public boolean checklistRestored;
        public String message;
    }

    public static String getStorageUsageSummary() {
        File dir = new File(BASE_DIR_PATH);
        if (!dir.exists()) return "0.0 MB ocupados | 0 ciclos salvos";

        long bytes = getFolderSize(dir);
        double mb = bytes / (1024.0 * 1024.0);
        List<CycleData> all = CycleData.loadAllCycles();
        return String.format(Locale.US, "%.1f MB em Documentos/OmniaOptimizer | %d ciclos", mb, all.size());
    }

    public static File getChecklistFile() {
        File dir = new File(BASE_DIR_PATH);
        dir.mkdirs();
        return new File(dir, CHECKLIST_FILE_NAME);
    }

    public static File createMonthlyZipBackup() {
        File baseDir = new File(BASE_DIR_PATH);
        File backupDir = new File(baseDir, "Backup");
        backupDir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM", Locale.US);
        String month = sdf.format(new Date());
        File zipFile = new File(backupDir, "Omnia-Backup-" + month + ".zip");

        try {
            FileOutputStream fos = new FileOutputStream(zipFile);
            ZipOutputStream zos = new ZipOutputStream(fos);

            File hist = CycleData.getHistoryFile();
            if (hist.exists()) {
                addFileToZip(zos, hist, "historico_vitalicio.json");
            }

            File checklist = getChecklistFile();
            if (checklist.exists()) {
                addFileToZip(zos, checklist, CHECKLIST_FILE_NAME);
            }

            File[] files = baseDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && f.getName().startsWith("checkup-")) {
                        addFolderToZip(zos, f, f.getName());
                    }
                }
            }

            zos.finish();
            zos.close();
            fos.close();
            return zipFile;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao criar backup ZIP", e);
            if (zipFile.exists()) {
                zipFile.delete();
            }
            return null;
        }
    }

    public static Intent getGoogleDriveBackupIntent(Context context, File zipFile) {
        if (zipFile == null || !zipFile.exists()) {
            zipFile = createMonthlyZipBackup();
        }
        if (zipFile == null || !zipFile.exists()) return null;

        Uri uri = BackupFileProvider.getUriForFile(zipFile);

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("application/zip");
        sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Backup Omnia Optimizer - " + zipFile.getName());
        sendIntent.putExtra(Intent.EXTRA_TEXT, "Backup consolidado de bateria e otimização (Omnia Optimizer).");
        sendIntent.setClipData(ClipData.newRawUri(zipFile.getName(), uri));
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent driveIntent = new Intent(sendIntent);
        driveIntent.setComponent(new ComponentName(
                "com.google.android.apps.docs",
                "com.google.android.apps.docs.common.shareitem.UploadMenuActivity"
        ));
        if (context.getPackageManager().resolveActivity(driveIntent, 0) != null) {
            return driveIntent;
        }

        return Intent.createChooser(sendIntent, "Salvar Backup no Google Drive / Nuvem");
    }

    public static Intent getFallbackShareIntent(File zipFile) {
        if (zipFile == null || !zipFile.exists()) return null;
        Uri uri = BackupFileProvider.getUriForFile(zipFile);
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("application/zip");
        sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Backup Omnia Optimizer - " + zipFile.getName());
        sendIntent.setClipData(ClipData.newRawUri(zipFile.getName(), uri));
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(sendIntent, "Salvar Backup na Nuvem");
    }

    public static RestoreResult restoreBackupFromUri(Context context, Uri uri) {
        RestoreResult res = new RestoreResult();
        if (uri == null) {
            res.message = "Nenhum arquivo selecionado.";
            return res;
        }

        File baseDir = new File(BASE_DIR_PATH);
        baseDir.mkdirs();

        String displayName = getDisplayName(context, uri).toLowerCase(Locale.US);
        boolean likelyJson = displayName.endsWith(".json");

        try {
            if (!likelyJson && !displayName.endsWith(".zip")) {
                InputStream sniff = context.getContentResolver().openInputStream(uri);
                if (sniff != null) {
                    int b = sniff.read();
                    sniff.close();
                    if (b == '[' || b == '{') likelyJson = true;
                }
            }

            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                res.message = "Não foi possível abrir o arquivo selecionado.";
                return res;
            }

            if (likelyJson) {
                String jsonText = readStreamAsString(is);
                is.close();
                List<CycleData> incoming = parseCyclesJson(jsonText);
                int[] stats = CycleData.mergeCycles(incoming, false);
                res.addedCycles = stats[0];
                res.updatedCycles = stats[1];
                res.success = true;
                res.message = String.format(Locale.US,
                        "JSON restaurado! +%d ciclos novos (%d atualizados).",
                        res.addedCycles, res.updatedCycles);
                return res;
            }

            String baseCanonical = baseDir.getCanonicalPath();
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry;
            byte[] buf = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;

                while (name.startsWith("/")) name = name.substring(1);
                if (name.contains("..")) {
                    zis.closeEntry();
                    continue;
                }

                if (name.equals("historico_vitalicio.json")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buf)) > 0) baos.write(buf, 0, len);
                    String jsonText = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    List<CycleData> incoming = parseCyclesJson(jsonText);
                    int[] stats = CycleData.mergeCycles(incoming, false);
                    res.addedCycles += stats[0];
                    res.updatedCycles += stats[1];
                    zis.closeEntry();
                    continue;
                }

                if (name.equals(CHECKLIST_FILE_NAME)) {
                    File outCheck = getChecklistFile();
                    FileOutputStream fos = new FileOutputStream(outCheck);
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.close();
                    res.checklistRestored = true;
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(baseDir, name);
                String outCanonical = outFile.getCanonicalPath();
                if (!outCanonical.startsWith(baseCanonical + File.separator)) {
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.close();
                    res.restoredFiles++;
                }
                zis.closeEntry();
            }

            zis.close();
            res.success = true;
            res.message = String.format(Locale.US,
                    "Restauração concluída! +%d ciclos novos, %d atualizados e %d arquivos.",
                    res.addedCycles, res.updatedCycles, res.restoredFiles);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao restaurar backup", e);
            res.success = false;
            res.message = "Falha na restauração: " + e.getMessage();
        }
        return res;
    }

    private static List<CycleData> parseCyclesJson(String jsonText) throws Exception {
        List<CycleData> list = new ArrayList<CycleData>();
        if (jsonText == null) return list;
        String trimmed = jsonText.trim();
        if (trimmed.isEmpty()) return list;
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                list.add(CycleData.fromJson(arr.getJSONObject(i)));
            }
        } else if (trimmed.startsWith("{")) {
            list.add(CycleData.fromJson(new org.json.JSONObject(trimmed)));
        }
        return list;
    }

    private static String getDisplayName(Context context, Uri uri) {
        String name = "";
        try {
            Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String path = uri.getLastPathSegment();
            if (path != null) name = path;
        }
        return name != null ? name : "";
    }

    private static String readStreamAsString(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    private static final int MAX_RAW_CHECKUP_FOLDERS = 12;
    private static final long MAX_RAW_CHECKUP_BYTES = 150L * 1024L * 1024L;

    public static void cleanupOldRawDumps(int keepDays) {
        File dir = new File(BASE_DIR_PATH);
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        List<File> checkups = new ArrayList<File>();
        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("checkup-")) {
                checkups.add(f);
            }
        }
        Collections.sort(checkups, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });

        long cutoff = System.currentTimeMillis() - (keepDays * 24L * 3600L * 1000L);
        long keptBytes = 0L;

        for (int i = 0; i < checkups.size(); i++) {
            File f = checkups.get(i);
            long folderBytes = getFolderSize(f);
            boolean tooOld = f.lastModified() < cutoff;
            boolean tooMany = i >= MAX_RAW_CHECKUP_FOLDERS;
            boolean tooLarge = i > 0 && (keptBytes + folderBytes) > MAX_RAW_CHECKUP_BYTES;

            if (i > 0 && (tooOld || tooMany || tooLarge)) {
                deleteRecursive(f);
            } else {
                keptBytes += folderBytes;
            }
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private static void addFolderToZip(ZipOutputStream zos, File folder, String parentPath) throws Exception {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            String path = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                addFolderToZip(zos, file, path);
            } else {
                addFileToZip(zos, file, path);
            }
        }
    }

    private static void addFileToZip(ZipOutputStream zos, File file, String entryName) throws Exception {
        byte[] buf = new byte[8192];
        FileInputStream fis = new FileInputStream(file);
        zos.putNextEntry(new ZipEntry(entryName));
        int len;
        while ((len = fis.read(buf)) > 0) {
            zos.write(buf, 0, len);
        }
        zos.closeEntry();
        fis.close();
    }

    private static long getFolderSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            size += f.isDirectory() ? getFolderSize(f) : f.length();
        }
        return size;
    }
}

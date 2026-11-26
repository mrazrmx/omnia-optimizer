package com.omnia.optimizer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Expõe arquivos .zip de backup via URI content:// (compatível com targetSdk 34+).
 */
public class BackupFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.omnia.optimizer.fileprovider";
    private static final String[] DEFAULT_PROJECTION = {
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
    };

    public static Uri getUriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("backup")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        File file = resolveSafeFile(uri);
        if (file == null) return "application/octet-stream";
        String name = file.getName().toLowerCase();
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolveSafeFile(uri);
        if (file == null || !file.exists()) {
            return null;
        }

        String[] cols = (projection != null && projection.length > 0) ? projection : DEFAULT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                row[i] = file.length();
            } else if ("mime_type".equals(col)) {
                row[i] = getType(uri);
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && (mode.contains("w") || mode.contains("W") || mode.contains("t") || mode.contains("T"))) {
            throw new SecurityException("BackupFileProvider permite apenas leitura.");
        }
        File file = resolveSafeFile(uri);
        if (file == null || !file.exists() || !file.isFile()) {
            throw new FileNotFoundException("Arquivo de backup não encontrado: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Inserção não suportada.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Exclusão não suportada.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Atualização não suportada.");
    }

    private File resolveSafeFile(Uri uri) {
        if (uri == null) return null;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 2) return null;
        if (!"backup".equals(segments.get(0))) return null;

        String fileName = segments.get(1);
        if (fileName == null || fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }

        File backupDir = new File(StorageManager.BASE_DIR_PATH, "Backup");
        File candidate = new File(backupDir, fileName);
        try {
            String dirCanonical = backupDir.getCanonicalPath();
            String fileCanonical = candidate.getCanonicalPath();
            if (!fileCanonical.startsWith(dirCanonical + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return candidate;
    }
}

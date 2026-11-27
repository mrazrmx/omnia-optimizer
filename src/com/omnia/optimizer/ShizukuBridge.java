package com.omnia.optimizer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import moe.shizuku.api.BinderContainer;

/**
 * Dependency-free Shizuku Binder IPC client verified against moe.shizuku.privileged.api.
 * Runs exclusively on-demand (UI actions / manual checks) with 0% background polling.
 */
public class ShizukuBridge {
    private static final String TAG = "OmniaShizuku";

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final Uri SHIZUKU_PROVIDER_URI = Uri.parse("content://moe.shizuku.privileged.api.shizuku");
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";

    private static final String DESCRIPTOR_SERVICE = "moe.shizuku.server.IShizukuService";
    private static final String DESCRIPTOR_APP = "moe.shizuku.server.IShizukuApplication";
    private static final String DESCRIPTOR_PROCESS = "moe.shizuku.server.IRemoteProcess";

    private static final int TR_GET_VERSION = 3;
    private static final int TR_GET_UID = 4;
    private static final int TR_NEW_PROCESS = 8;
    private static final int TR_ATTACH_APP_V12 = 14;
    private static final int TR_REQUEST_PERMISSION = 15;
    private static final int TR_CHECK_SELF_PERMISSION = 16;
    private static final int TR_ATTACH_APP_V13 = 18;

    private static final int APP_TR_BIND = 2;
    private static final int APP_TR_PERMISSION_RESULT = 3;

    private static final int PROC_TR_INPUT_STREAM = 2;
    private static final int PROC_TR_ERROR_STREAM = 3;
    private static final int PROC_TR_EXIT_VALUE = 5;
    private static final int PROC_TR_DESTROY = 6;
    private static final int PROC_TR_WAIT_TIMEOUT = 8;

    private static final String PREF_NAME = "omnia_shizuku_cache";
    private static final String KEY_BAT_ASOC = "bat_asoc";
    private static final String KEY_BAT_BSOH = "bat_bsoh";
    private static final String KEY_BAT_CYCLES = "bat_cycles";
    private static final String KEY_BAT_FIRST_USE = "bat_first_use";
    private static final String KEY_BAT_UPDATED_AT = "bat_updated_at";
    private static final String KEY_OVERLAY_LIST = "overlay_list_cache";

    private static final Object ATTACH_LOCK = new Object();
    private static volatile IBinder serviceBinder;
    private static volatile boolean attached = false;
    private static volatile boolean permGranted = false;
    private static volatile int serverUid = -1;
    private static volatile int serverVersion = -1;
    private static volatile PermissionCallback pendingPermissionCallback;
    private static final CountDownLatch[] binderLatchHolder = new CountDownLatch[1];

    public interface PermissionCallback {
        void onResult(boolean allowed);
    }

    public static class ShizukuStatus {
        public boolean managerInstalled;
        public boolean serviceRunning;
        public boolean permissionGranted;
        public int serverUid = -1;
        public String detail = "";
    }

    public static class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final String error;

        public ExecResult(int exitCode, String stdout, String stderr, String error) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.error = error;
        }

        public boolean isOk() {
            return error == null && exitCode == 0;
        }
    }

    public static class ChemicalBatteryCache {
        public double asoc = -1;
        public double bsoh = -1;
        public double cycles = -1;
        public String firstUseDate = "";
        public long updatedAt = 0;

        public boolean hasUsefulData() {
            return asoc > 0 || cycles > 0 || bsoh > 0 || !firstUseDate.isEmpty();
        }
    }

    private static final Binder APP_CALLBACK_BINDER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == APP_TR_BIND) {
                data.enforceInterface(DESCRIPTOR_APP);
                if (data.readInt() != 0) {
                    Bundle b = Bundle.CREATOR.createFromParcel(data);
                    serverUid = b.getInt("shizuku:attach-reply-uid", serverUid);
                    serverVersion = b.getInt("shizuku:attach-reply-version", serverVersion);
                    permGranted = b.getBoolean("shizuku:attach-reply-permission-granted", permGranted);
                    attached = true;
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == APP_TR_PERMISSION_RESULT) {
                data.enforceInterface(DESCRIPTOR_APP);
                data.readInt(); // requestCode
                boolean allowed = false;
                if (data.readInt() != 0) {
                    Bundle b = Bundle.CREATOR.createFromParcel(data);
                    allowed = b.getBoolean("shizuku:request-permission-reply-allowed", false);
                }
                permGranted = allowed;
                PermissionCallback cb = pendingPermissionCallback;
                pendingPermissionCallback = null;
                if (cb != null) {
                    cb.onResult(allowed);
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public static IBinder peekBinder() {
        return serviceBinder;
    }

    public static void onBinderReceived(Context context, IBinder binder) {
        if (binder == null || !binder.pingBinder()) return;
        CountDownLatch latch = binderLatchHolder[0];
        if (latch != null) latch.countDown();
        synchronized (ATTACH_LOCK) {
            if (attached && serviceBinder == binder && binder.pingBinder()) {
                return;
            }
            if (serviceBinder != binder) {
                attached = false;
                serverUid = -1;
                serverVersion = -1;
                permGranted = false;
                serviceBinder = binder;
            }
            if (!attached && context != null) {
                attachInternalLocked(context.getApplicationContext(), binder);
            }
        }
    }

    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean ensureAttached(Context context) {
        Context appCtx = context.getApplicationContext();
        synchronized (ATTACH_LOCK) {
            IBinder b = serviceBinder;
            if (b != null && b.pingBinder() && attached) {
                return true;
            }
        }

        IBinder fetched = fetchBinderFromManager(appCtx);
        if (fetched == null) {
            requestBinderPush(appCtx);
        }

        synchronized (ATTACH_LOCK) {
            IBinder b = (fetched != null && fetched.pingBinder()) ? fetched : serviceBinder;
            if (b == null || !b.pingBinder()) {
                attached = false;
                permGranted = false;
                return false;
            }
            if (serviceBinder != b) {
                attached = false;
                serverUid = -1;
                serverVersion = -1;
                permGranted = false;
                serviceBinder = b;
            }
            if (!attached) {
                attachInternalLocked(appCtx, b);
            }
            return attached;
        }
    }

    private static IBinder fetchBinderFromManager(Context context) {
        try {
            Bundle reply = context.getContentResolver().call(SHIZUKU_PROVIDER_URI, "getBinder", null, new Bundle());
            if (reply != null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer container = reply.getParcelable(EXTRA_BINDER);
                if (container != null && container.binder != null && container.binder.pingBinder()) {
                    return container.binder;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "getBinder unavailable: " + t.getMessage());
        }
        return null;
    }

    private static void requestBinderPush(Context context) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            binderLatchHolder[0] = latch;
            Intent intent = new Intent(ACTION_REQUEST_BINDER);
            intent.setPackage(SHIZUKU_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            latch.await(350, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.d(TAG, "requestBinderPush failed: " + t.getMessage());
        } finally {
            binderLatchHolder[0] = null;
        }
    }

    private static boolean attachInternalLocked(Context context, IBinder binder) {
        if (attached && serviceBinder == binder && binder.pingBinder()) {
            return true;
        }
        Throwable v13Err = tryTransactAttach(context, binder, true);
        if (v13Err == null) {
            return finalizeAttachedLocked(binder);
        }
        if (isAlreadyAttachedNpe(v13Err) && probeAlreadyAttachedLocked(binder)) {
            return true;
        }
        Throwable v12Err = tryTransactAttach(context, binder, false);
        if (v12Err == null) {
            return finalizeAttachedLocked(binder);
        }
        if (isAlreadyAttachedNpe(v12Err) && probeAlreadyAttachedLocked(binder)) {
            return true;
        }
        Log.w(TAG, "attachInternal error", v13Err);
        attached = false;
        return false;
    }

    private static Throwable tryTransactAttach(Context context, IBinder binder, boolean v13) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE);
            data.writeStrongBinder(APP_CALLBACK_BINDER);
            if (v13) {
                Bundle args = new Bundle();
                args.putInt("shizuku:attach-api-version", 13);
                args.putString("shizuku:attach-package-name", context.getPackageName());
                data.writeInt(1);
                args.writeToParcel(data, 0);
                binder.transact(TR_ATTACH_APP_V13, data, reply, 0);
            } else {
                data.writeString(context.getPackageName());
                binder.transact(TR_ATTACH_APP_V12, data, reply, 0);
            }
            reply.readException();
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean finalizeAttachedLocked(IBinder binder) {
        attached = true;
        if (serverUid < 0) serverUid = queryInt(binder, TR_GET_UID, -1);
        if (serverVersion < 0) serverVersion = queryInt(binder, TR_GET_VERSION, 13);
        permGranted = checkSelfPermissionInternal(binder);
        return true;
    }

    private static boolean isAlreadyAttachedNpe(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        return msg != null && (msg.contains("o6.f") || msg.contains("ShizukuService.r"));
    }

    private static boolean probeAlreadyAttachedLocked(IBinder binder) {
        int probeUid = queryInt(binder, TR_GET_UID, -1);
        if (probeUid >= 0) {
            serverUid = probeUid;
            if (serverVersion < 0) serverVersion = queryInt(binder, TR_GET_VERSION, 13);
            attached = true;
            permGranted = checkSelfPermissionInternal(binder);
            return true;
        }
        return false;
    }

    private static boolean checkSelfPermissionInternal(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE);
            binder.transact(TR_CHECK_SELF_PERMISSION, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            return permGranted;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int queryInt(IBinder binder, int code, int fallback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return fallback;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean hasPermission(Context context) {
        return getStatus(context).permissionGranted;
    }

    public static ShizukuStatus getStatus(Context context) {
        ShizukuStatus st = new ShizukuStatus();
        st.managerInstalled = isShizukuInstalled(context);
        st.serviceRunning = ensureAttached(context);
        if (st.serviceRunning) {
            IBinder b = serviceBinder;
            permGranted = checkSelfPermissionInternal(b);
            st.permissionGranted = permGranted;
            st.serverUid = serverUid;
            if (st.permissionGranted) {
                st.detail = String.format(Locale.US, "Conectado · Autorizado (UID %d · API v%d)",
                        st.serverUid, Math.max(13, serverVersion));
            } else {
                st.detail = String.format(Locale.US, "Shizuku ativo (UID %d) · Aguardando sua autorização", st.serverUid);
            }
        } else if (st.managerInstalled) {
            st.detail = "Shizuku instalado, mas o serviço está parado (ative na Depuração Wi-Fi).";
        } else {
            st.detail = "Shizuku não encontrado neste aparelho.";
        }
        Log.i(TAG, "ShizukuStatus: " + st.detail);
        return st;
    }

    public static void requestPermission(Context context, PermissionCallback callback) {
        if (!ensureAttached(context)) {
            if (callback != null) callback.onResult(false);
            return;
        }
        IBinder b = serviceBinder;
        if (checkSelfPermissionInternal(b)) {
            permGranted = true;
            if (callback != null) callback.onResult(true);
            return;
        }
        pendingPermissionCallback = callback;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE);
            data.writeInt(4801);
            b.transact(TR_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            pendingPermissionCallback = null;
            if (callback != null) callback.onResult(false);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public interface ShellStreamListener {
        void onLine(String line, boolean stderr);
        void onCompleted(int exitCode, String error);
    }

    public static final class ShellSession {
        private final IBinder procBinder;
        private volatile boolean cancelled = false;

        ShellSession(IBinder procBinder) {
            this.procBinder = procBinder;
        }

        public void cancel() {
            cancelled = true;
            if (procBinder != null) {
                destroyProcess(procBinder);
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static IBinder startShellProcess(Context context, String command, String[] errOut) {
        if (!ensureAttached(context)) {
            if (errOut != null && errOut.length > 0) errOut[0] = "Serviço Shizuku offline.";
            return null;
        }
        IBinder b = serviceBinder;
        if (!checkSelfPermissionInternal(b)) {
            if (errOut != null && errOut.length > 0) errOut[0] = "Omnia ainda não autorizado no Shizuku.";
            return null;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE);
            data.writeStringArray(new String[]{"/system/bin/sh", "-c", command});
            data.writeStringArray(null);
            data.writeString(null);
            b.transact(TR_NEW_PROCESS, data, reply, 0);
            reply.readException();
            IBinder procBinder = reply.readStrongBinder();
            if (procBinder == null && errOut != null && errOut.length > 0) {
                errOut[0] = "Processo remoto nulo.";
            }
            return procBinder;
        } catch (Throwable t) {
            if (errOut != null && errOut.length > 0) errOut[0] = "Falha IPC newProcess: " + t.getMessage();
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static ExecResult exec(Context context, String command, long timeoutMs) {
        String[] err = new String[]{null};
        IBinder procBinder = startShellProcess(context, command, err);
        if (procBinder == null) {
            return new ExecResult(-1, "", "", err[0] != null ? err[0] : "Falha ao iniciar processo remoto.");
        }

        final ParcelFileDescriptor pfdOut = readProcessStream(procBinder, PROC_TR_INPUT_STREAM);
        final ParcelFileDescriptor pfdErr = readProcessStream(procBinder, PROC_TR_ERROR_STREAM);
        final String[] outHolder = new String[]{""};
        final String[] errHolder = new String[]{""};

        Thread tOut = startDrainThread(pfdOut, outHolder, "omnia-sh-out");
        Thread tErr = startDrainThread(pfdErr, errHolder, "omnia-sh-err");

        boolean exited = waitProcessTimeout(procBinder, timeoutMs > 0 ? timeoutMs : 12000L);
        int exitCode = exited ? readProcessInt(procBinder, PROC_TR_EXIT_VALUE, -1) : -2;
        if (!exited) {
            destroyProcess(procBinder);
        }

        try { if (tOut != null) tOut.join(1200); } catch (InterruptedException ignored) {}
        try { if (tErr != null) tErr.join(1200); } catch (InterruptedException ignored) {}

        return new ExecResult(exitCode, outHolder[0], errHolder[0], exited ? null : "Timeout ao executar comando");
    }

    private static ParcelFileDescriptor readProcessStream(IBinder proc, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_PROCESS);
            proc.transact(code, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 0) {
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            }
        } catch (Throwable t) {
            Log.d(TAG, "readProcessStream failed: " + t.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    private static Thread startDrainThread(final ParcelFileDescriptor pfd, final String[] holder, String name) {
        if (pfd == null) return null;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (ParcelFileDescriptor autoClose = pfd;
                     InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(autoClose)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (baos.size() < 4194304) {
                            baos.write(buf, 0, n);
                        }
                    }
                    holder[0] = baos.toString(StandardCharsets.UTF_8.name()).trim();
                } catch (Throwable ignored) {}
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean waitProcessTimeout(IBinder proc, long timeoutMs) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_PROCESS);
            data.writeLong(timeoutMs);
            data.writeString("MILLISECONDS");
            proc.transact(PROC_TR_WAIT_TIMEOUT, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int readProcessInt(IBinder proc, int code, int fallback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_PROCESS);
            proc.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return fallback;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void destroyProcess(IBinder proc) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_PROCESS);
            proc.transact(PROC_TR_DESTROY, data, reply, 0);
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static ExecResult grantOmniaPermissions(Context context) {
        String pkg = context.getPackageName();
        String cmd = "pm grant " + pkg + " android.permission.DUMP && " +
                "pm grant " + pkg + " android.permission.BATTERY_STATS && " +
                "pm grant " + pkg + " android.permission.PACKAGE_USAGE_STATS && " +
                "cmd appops set " + pkg + " GET_USAGE_STATS allow && " +
                "cmd appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow && " +
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow && " +
                "dumpsys deviceidle whitelist +" + pkg + "; " +
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true";
        return exec(context, cmd, 15000L);
    }

    public static List<String> applyRecommendedBatteryTweaks(Context context) {
        ContentResolver cr = context.getContentResolver();
        List<String> changes = new ArrayList<String>();
        StringBuilder cmd = new StringBuilder();

        if (!"0".equals(AnalyticsEngine.safeGlobal(cr, "wifi_scan_always_enabled"))) {
            cmd.append("settings put global wifi_scan_always_enabled 0; ");
            changes.add("Varredura Wi-Fi 2º plano = 0");
        }
        if (!"0".equals(AnalyticsEngine.safeGlobal(cr, "ble_scan_always_enabled"))) {
            cmd.append("settings put global ble_scan_always_enabled 0; ");
            changes.add("Varredura BLE 2º plano = 0");
        }
        if (!"0".equals(AnalyticsEngine.safeSystem(cr, "nearby_scanning_enabled"))) {
            cmd.append("settings put system nearby_scanning_enabled 0; ");
            changes.add("Busca de aparelhos próximos = 0");
        }
        String timeout = AnalyticsEngine.safeSystem(cr, "screen_off_timeout");
        try {
            if (timeout != null && Long.parseLong(timeout) > 60000L) {
                cmd.append("settings put system screen_off_timeout 60000; ");
                changes.add("Tempo limite tela = 60s");
            }
        } catch (NumberFormatException ignored) {}

        if (cmd.length() == 0) {
            return changes;
        }
        ExecResult res = exec(context, cmd.toString(), 10000L);
        if (!res.isOk()) {
            changes.clear();
            changes.add("ERRO: " + (res.error != null ? res.error : res.stderr));
        }
        return changes;
    }

    /**
     * Reads chemical battery health & Tweaks overlay presence using Shizuku UID 2000 shell
     * (Android blocks apps from seeing mSavedBatteryAsoc / mSavedBatteryUsage directly).
     */
    public static ChemicalBatteryCache refreshChemicalHealthAndOverlays(Context context) {
        String cmd = "dumpsys battery; echo '---SEC_AUTH---'; " +
                "for f in asoc batt_discharge_level first_use_date presence batt_auth bsoh; do " +
                "if [ -r \"/sys/class/power_supply/sec_auth/$f\" ]; then echo \"SEC_$f=$(cat /sys/class/power_supply/sec_auth/$f 2>/dev/null)\"; fi; " +
                "done; echo '---OVERLAYS---'; " +
                "cmd overlay list 2>/dev/null | grep -E 's23tweaks|aodbright|vulkan' || true";
        ExecResult res = exec(context, cmd, 10000L);
        if (!res.isOk() && res.stdout.isEmpty()) {
            return loadCachedChemicalHealth(context);
        }

        ChemicalBatteryCache cache = new ChemicalBatteryCache();
        String text = res.stdout;
        Matcher mAsoc = Pattern.compile("mSavedBatteryAsoc:\\s*\\[?([0-9]+(?:\\.[0-9]+)?)\\]?").matcher(text);
        if (mAsoc.find()) cache.asoc = parseDoubleSafe(mAsoc.group(1), -1);

        Matcher mSecAsoc = Pattern.compile("SEC_asoc=([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        if (cache.asoc <= 0 && mSecAsoc.find()) cache.asoc = parseDoubleSafe(mSecAsoc.group(1), -1);

        Matcher mUsage = Pattern.compile("mSavedBatteryUsage:\\s*\\[?([0-9]+(?:\\.[0-9]+)?)\\]?").matcher(text);
        if (mUsage.find()) cache.cycles = parseDoubleSafe(mUsage.group(1), -1) / 100.0;

        Matcher mSecDis = Pattern.compile("SEC_batt_discharge_level=([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        if (cache.cycles <= 0 && mSecDis.find()) cache.cycles = parseDoubleSafe(mSecDis.group(1), -1) / 100.0;

        Matcher mFirst = Pattern.compile("FirstUseDate(?:Data\\s+efsValue)?:\\s*\\[?([0-9]{8})\\]?").matcher(text);
        if (mFirst.find()) cache.firstUseDate = mFirst.group(1).trim();
        else {
            Matcher mSecFirst = Pattern.compile("SEC_first_use_date=([0-9]+)").matcher(text);
            if (mSecFirst.find()) cache.firstUseDate = mSecFirst.group(1).trim();
        }

        Matcher mBsoh = Pattern.compile("(?:mSavedBatteryBsoh|LLB BSOH):\\s*\\[?([0-9]+(?:\\.[0-9]+)?)\\]?").matcher(text);
        if (mBsoh.find()) cache.bsoh = parseDoubleSafe(mBsoh.group(1), -1);
        else {
            Matcher mSecBsoh = Pattern.compile("SEC_bsoh=([0-9]+(?:\\.[0-9]+)?)").matcher(text);
            if (mSecBsoh.find()) cache.bsoh = parseDoubleSafe(mSecBsoh.group(1), -1);
        }

        cache.updatedAt = System.currentTimeMillis();
        String overlaySection = "";
        int ovIdx = text.indexOf("---OVERLAYS---");
        if (ovIdx >= 0) overlaySection = text.substring(ovIdx + "---OVERLAYS---".length()).trim();

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        if (cache.asoc > 0) ed.putFloat(KEY_BAT_ASOC, (float) cache.asoc);
        if (cache.bsoh > 0) ed.putFloat(KEY_BAT_BSOH, (float) cache.bsoh);
        if (cache.cycles > 0) ed.putFloat(KEY_BAT_CYCLES, (float) cache.cycles);
        if (!cache.firstUseDate.isEmpty()) ed.putString(KEY_BAT_FIRST_USE, cache.firstUseDate);
        ed.putLong(KEY_BAT_UPDATED_AT, cache.updatedAt);
        ed.putString(KEY_OVERLAY_LIST, overlaySection);
        ed.apply();
        return cache;
    }

    public static ChemicalBatteryCache loadCachedChemicalHealth(Context context) {
        ChemicalBatteryCache c = new ChemicalBatteryCache();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            c.asoc = prefs.getFloat(KEY_BAT_ASOC, -1f);
            c.bsoh = prefs.getFloat(KEY_BAT_BSOH, -1f);
            c.cycles = prefs.getFloat(KEY_BAT_CYCLES, -1f);
            c.firstUseDate = prefs.getString(KEY_BAT_FIRST_USE, "");
            c.updatedAt = prefs.getLong(KEY_BAT_UPDATED_AT, 0L);
        } catch (Throwable ignored) {}
        return c;
    }

    public static String getCachedOverlayList(Context context) {
        try {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_OVERLAY_LIST, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static ShellSession execStreaming(Context context, String command, final ShellStreamListener listener) {
        String[] err = new String[]{null};
        final IBinder procBinder = startShellProcess(context, command, err);
        if (procBinder == null) {
            if (listener != null) {
                listener.onCompleted(-1, err[0] != null ? err[0] : "Falha ao iniciar processo remoto.");
            }
            return null;
        }

        final ShellSession session = new ShellSession(procBinder);
        final ParcelFileDescriptor pfdOut = readProcessStream(procBinder, PROC_TR_INPUT_STREAM);
        final ParcelFileDescriptor pfdErr = readProcessStream(procBinder, PROC_TR_ERROR_STREAM);

        Thread runner = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread tOut = startLineStreamThread(pfdOut, false, listener, "omnia-sh-stream-out");
                Thread tErr = startLineStreamThread(pfdErr, true, listener, "omnia-sh-stream-err");
                try { if (tOut != null) tOut.join(); } catch (InterruptedException ignored) {}
                try { if (tErr != null) tErr.join(); } catch (InterruptedException ignored) {}
                boolean exited = waitProcessTimeout(procBinder, 3000L);
                int exitCode = exited ? readProcessInt(procBinder, PROC_TR_EXIT_VALUE, -1) : -1;
                if (listener != null) {
                    listener.onCompleted(exitCode, session.isCancelled() ? "Interrompido pelo usuário." : null);
                }
            }
        }, "omnia-sh-stream-wait");
        runner.setDaemon(true);
        runner.start();
        return session;
    }

    private static Thread startLineStreamThread(final ParcelFileDescriptor pfd, final boolean isErr,
                                                final ShellStreamListener listener, String name) {
        if (pfd == null) return null;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (ParcelFileDescriptor autoClose = pfd;
                     InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(autoClose);
                     BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (listener != null) {
                            listener.onLine(line, isErr);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static double parseDoubleSafe(String raw, double def) {
        try { return Double.parseDouble(raw); } catch (Throwable t) { return def; }
    }
}

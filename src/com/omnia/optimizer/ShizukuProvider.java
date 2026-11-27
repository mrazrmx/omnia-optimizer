package com.omnia.optimizer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import moe.shizuku.api.BinderContainer;

/**
 * Minimal Shizuku v3 ContentProvider endpoint (<pkg>.shizuku) so Shizuku Server
 * can push/query the live IShizukuService binder directly to Omnia.
 */
public class ShizukuProvider extends ContentProvider {
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String METHOD_GET_BINDER = "getBinder";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info != null) {
            if (info.multiprocess) {
                throw new IllegalStateException("android:multiprocess must be false");
            }
            if (!info.exported) {
                throw new IllegalStateException("android:exported must be true");
            }
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method == null) return new Bundle();
        Bundle reply = new Bundle();
        if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = ShizukuBridge.peekBinder();
            if (binder != null && binder.pingBinder()) {
                reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
                return reply;
            }
            return null;
        }
        if (METHOD_SEND_BINDER.equals(method) && extras != null) {
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                ShizukuBridge.onBinderReceived(getContext(), container.binder);
            }
        }
        return reply;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

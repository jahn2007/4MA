package io.github.jahn2007.fourma;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Write-only IPC endpoint used by the injected WeChat process to persist sanitized reports. */
public class ReportProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.jahn2007.fourma.reports";
    public static final String METHOD_APPEND = "append";
    public static final String METHOD_RECORD_HOT_RELOAD = "record_hot_reload";
    public static final String EXTRA_REPORT_KEY = "report_key";
    public static final String EXTRA_LINE = "line";
    private static final int MAX_REPORT_CHARS = 28_000;
    private final Object writeLock = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null || !callerIsAllowed()) {
            return Bundle.EMPTY;
        }
        String key = extras.getString(EXTRA_REPORT_KEY, "");
        if (!Prefs.LOG_KEYS.contains(key)) return Bundle.EMPTY;
        if (METHOD_RECORD_HOT_RELOAD.equals(method)) {
            synchronized (writeLock) {
                getContext().getSharedPreferences(Prefs.REPORT_STORE,
                                android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong(Prefs.hotReloadKey(key), System.currentTimeMillis())
                        .apply();
            }
            Bundle result = new Bundle();
            result.putBoolean("accepted", true);
            return result;
        }
        if (!METHOD_APPEND.equals(method)) return Bundle.EMPTY;
        String line = extras.getString(EXTRA_LINE, "");
        if (line.isEmpty() || line.length() > 800
                || line.indexOf('\r') >= 0 || !line.endsWith("\n")
                || line.substring(0, line.length() - 1).indexOf('\n') >= 0) {
            return Bundle.EMPTY;
        }
        synchronized (writeLock) {
            SharedPreferences reports = getContext().getSharedPreferences(
                    Prefs.REPORT_STORE, android.content.Context.MODE_PRIVATE);
            String next = reports.getString(key, "") + line;
            if (next.length() > MAX_REPORT_CHARS) {
                int cut = next.indexOf('\n', next.length() - MAX_REPORT_CHARS);
                next = cut >= 0 ? next.substring(cut + 1)
                        : next.substring(next.length() - MAX_REPORT_CHARS);
            }
            reports.edit()
                    .putString(key, next)
                    .putLong(Prefs.KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply();
        }
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private boolean callerIsAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        PackageManager manager = getContext().getPackageManager();
        String[] packages = manager.getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            if ("com.tencent.mm".equals(name)) return true;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
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
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}

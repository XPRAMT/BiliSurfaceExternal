package dev.xpramt.bilisurfacebridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * Read-only view of the module preferences for frameworks that cannot share the XML file
 * (NPatch manager mode: XSharedPreferences points at this app's private data directory).
 * Only Bilibili and this app may read it.
 */
public final class ModulePreferencesProvider extends ContentProvider {
    static final String AUTHORITY = ModulePreferences.PACKAGE_NAME + ".prefs";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_SNAPSHOT = "snapshot";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_SNAPSHOT.equals(method)) {
            return null;
        }
        String caller = getCallingPackage();
        if (!BILIBILI_PACKAGE.equals(caller) && !ModulePreferences.PACKAGE_NAME.equals(caller)) {
            return null;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        Bundle snapshot = new Bundle();
        Map<String, ?> values = context.getSharedPreferences(
                ModulePreferences.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                snapshot.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof String) {
                snapshot.putString(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                snapshot.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                snapshot.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                snapshot.putFloat(entry.getKey(), (Float) value);
            }
        }
        return snapshot;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
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

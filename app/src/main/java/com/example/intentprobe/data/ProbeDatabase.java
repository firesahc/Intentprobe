package com.example.intentprobe.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class ProbeDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "intentprobe.db";
    private static final int DB_VERSION = 2;

    private static volatile ProbeDatabase instance;

    public static ProbeDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ProbeDatabase.class) {
                if (instance == null) {
                    instance = new ProbeDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ProbeDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + DbContract.TABLE_APPS + " ("
                + DbContract.COL_PKG + " TEXT PRIMARY KEY, "
                + DbContract.COL_LABEL + " TEXT NOT NULL, "
                + DbContract.COL_VERSION_CODE + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_LAST_UPDATE_TIME + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_APK_PATH + " TEXT, "
                + DbContract.COL_APK_MTIME + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_TARGET_SDK + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_IS_SYSTEM + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_ENABLED + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_LAST_SCAN_AT + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_SCAN_ERROR + " TEXT)");

        db.execSQL("CREATE TABLE " + DbContract.TABLE_ROUTES + " ("
                + DbContract.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + DbContract.COL_PKG + " TEXT NOT NULL, "
                + DbContract.COL_ACTIVITY + " TEXT NOT NULL, "
                + DbContract.COL_ALIAS + " TEXT, "
                + DbContract.COL_EXPORTED + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_EXPORTED_INFERRED + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_COMPONENT_ENABLED + " INTEGER NOT NULL DEFAULT 1, "
                + DbContract.COL_LAUNCH_MODE + " TEXT, "
                + DbContract.COL_PERMISSION + " TEXT, "
                + DbContract.COL_ACTION + " TEXT, "
                + DbContract.COL_CATEGORIES + " TEXT, "
                + DbContract.COL_SCHEME + " TEXT, "
                + DbContract.COL_HOST + " TEXT, "
                + DbContract.COL_PORT + " TEXT, "
                + DbContract.COL_PATH + " TEXT, "
                + DbContract.COL_PATH_PREFIX + " TEXT, "
                + DbContract.COL_PATH_PATTERN + " TEXT, "
                + DbContract.COL_PATH_SUFFIX + " TEXT, "
                + DbContract.COL_MIME_TYPE + " TEXT, "
                + DbContract.COL_URI + " TEXT, "
                + DbContract.COL_PATTERN_ONLY + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_FILTER_INDEX + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_ORIGIN + " TEXT NOT NULL DEFAULT 'manifest')");
        db.execSQL("CREATE INDEX idx_routes_pkg ON " + DbContract.TABLE_ROUTES
                + "(" + DbContract.COL_PKG + ")");

        db.execSQL("CREATE TABLE " + DbContract.TABLE_CAPTURES + " ("
                + DbContract.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + DbContract.COL_PKG + " TEXT NOT NULL, "
                + DbContract.COL_PROCESS + " TEXT, "
                + DbContract.COL_PID + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_SOURCE + " TEXT NOT NULL, "
                + DbContract.COL_TS + " INTEGER NOT NULL, "
                + DbContract.COL_TARGET_ACTIVITY + " TEXT, "
                + DbContract.COL_TARGET_PKG + " TEXT, "
                + DbContract.COL_CALLER + " TEXT, "
                + DbContract.COL_ACTION + " TEXT, "
                + DbContract.COL_DATA + " TEXT, "
                + DbContract.COL_TYPE + " TEXT, "
                + DbContract.COL_CATEGORIES + " TEXT, "
                + DbContract.COL_FLAGS + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_EXTRAS_JSON + " TEXT)");
        db.execSQL("CREATE INDEX idx_captures_pkg_ts ON " + DbContract.TABLE_CAPTURES
                + "(" + DbContract.COL_PKG + ", " + DbContract.COL_TS + " DESC)");

        db.execSQL("CREATE TABLE " + DbContract.TABLE_CONNECTIONS + " ("
                + DbContract.COL_PKG + " TEXT NOT NULL, "
                + DbContract.COL_PROCESS + " TEXT NOT NULL, "
                + DbContract.COL_PID + " INTEGER NOT NULL DEFAULT 0, "
                + DbContract.COL_LAST_SEEN + " INTEGER NOT NULL, "
                + DbContract.COL_MODULE_VERSION + " TEXT, "
                + "PRIMARY KEY(" + DbContract.COL_PKG + ", " + DbContract.COL_PROCESS + "))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + DbContract.TABLE_CAPTURES
                    + " ADD COLUMN " + DbContract.COL_TARGET_PKG + " TEXT");
            db.execSQL("ALTER TABLE " + DbContract.TABLE_CAPTURES
                    + " ADD COLUMN " + DbContract.COL_CALLER + " TEXT");
            db.execSQL("ALTER TABLE " + DbContract.TABLE_ROUTES
                    + " ADD COLUMN " + DbContract.COL_ORIGIN
                    + " TEXT NOT NULL DEFAULT 'manifest'");
        }
    }
}

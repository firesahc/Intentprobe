package com.example.intentprobe.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.intentprobe.model.AppEntry;
import com.example.intentprobe.model.CaptureEntry;
import com.example.intentprobe.model.ConnectionEntry;
import com.example.intentprobe.model.RouteCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app process is the only writer of probe routes, captures and connection
 * state. The provider forwards hook events into the same repository.
 */
public class ProbeRepository {

    private final ProbeDatabase helper;

    public ProbeRepository(Context context) {
        helper = ProbeDatabase.getInstance(context.getApplicationContext());
    }

    private SQLiteDatabase db() {
        return helper.getWritableDatabase();
    }

    // ── Apps ──

    public void upsertApps(List<AppEntry> apps) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (AppEntry app : apps) {
                long oldVersion = -1;
                long oldMtime = -1;
                try (Cursor c = db.query(DbContract.TABLE_APPS,
                        new String[]{DbContract.COL_VERSION_CODE, DbContract.COL_APK_MTIME},
                        DbContract.COL_PKG + "=?", new String[]{app.pkg},
                        null, null, null, "1")) {
                    if (c.moveToFirst()) {
                        oldVersion = c.getLong(0);
                        oldMtime = c.getLong(1);
                    }
                }
                ContentValues insert = appValues(app);
                insert.put(DbContract.COL_ENABLED, 0);
                db.insertWithOnConflict(DbContract.TABLE_APPS, null, insert,
                        SQLiteDatabase.CONFLICT_IGNORE);
                db.update(DbContract.TABLE_APPS, appValues(app),
                        DbContract.COL_PKG + "=?", new String[]{app.pkg});
                // A new APK build may change components and filters, so old
                // test results no longer describe it.
                if (oldVersion >= 0 && (oldVersion != app.versionCode
                        || oldMtime != app.apkMtime)) {
                    db.delete(DbContract.TABLE_ROUTE_TESTS,
                            DbContract.COL_PKG + "=?", new String[]{app.pkg});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<AppEntry> getApps() {
        List<AppEntry> result = new ArrayList<>();
        try (Cursor c = db().query(DbContract.TABLE_APPS, null, null, null,
                null, null, DbContract.COL_LABEL + " COLLATE NOCASE ASC")) {
            while (c.moveToNext()) {
                result.add(readApp(c));
            }
        }
        return result;
    }

    public AppEntry getApp(String pkg) {
        try (Cursor c = db().query(DbContract.TABLE_APPS, null,
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null, null, "1")) {
            return c.moveToFirst() ? readApp(c) : null;
        }
    }

    public Map<String, Boolean> getEnabledMap() {
        Map<String, Boolean> result = new HashMap<>();
        try (Cursor c = db().query(DbContract.TABLE_APPS,
                new String[]{DbContract.COL_PKG, DbContract.COL_ENABLED},
                null, null, null, null, null)) {
            while (c.moveToNext()) {
                result.put(c.getString(0), c.getInt(1) != 0);
            }
        }
        return result;
    }

    public boolean setEnabled(String pkg, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put(DbContract.COL_ENABLED, enabled ? 1 : 0);
        int rows = db().update(DbContract.TABLE_APPS, values,
                DbContract.COL_PKG + "=?", new String[]{pkg});
        if (rows > 0) return true;
        if (!enabled) return true;

        ContentValues insert = new ContentValues();
        insert.put(DbContract.COL_PKG, pkg);
        insert.put(DbContract.COL_LABEL, pkg);
        insert.put(DbContract.COL_ENABLED, 1);
        return db().insertWithOnConflict(DbContract.TABLE_APPS, null, insert,
                SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public boolean isPackageKnown(String pkg) {
        try (Cursor c = db().query(DbContract.TABLE_APPS,
                new String[]{DbContract.COL_PKG},
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null, null, "1")) {
            return c.moveToFirst();
        }
    }

    public boolean isPackageEnabled(String pkg) {
        try (Cursor c = db().query(DbContract.TABLE_APPS,
                new String[]{DbContract.COL_ENABLED},
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null, null, "1")) {
            return c.moveToFirst() && c.getInt(0) != 0;
        }
    }

    // ── Routes ──

    public void replaceRoutes(String pkg, List<RouteCandidate> routes, long scanAt) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            db.delete(DbContract.TABLE_ROUTES, DbContract.COL_PKG + "=?", new String[]{pkg});
            for (RouteCandidate route : routes) {
                db.insert(DbContract.TABLE_ROUTES, null, routeValues(route));
            }
            ContentValues app = new ContentValues();
            app.put(DbContract.COL_LAST_SCAN_AT, scanAt);
            app.putNull(DbContract.COL_SCAN_ERROR);
            db.update(DbContract.TABLE_APPS, app,
                    DbContract.COL_PKG + "=?", new String[]{pkg});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void markScanError(String pkg, String error) {
        ContentValues app = new ContentValues();
        app.put(DbContract.COL_SCAN_ERROR, error == null ? "" : error);
        db().update(DbContract.TABLE_APPS, app,
                DbContract.COL_PKG + "=?", new String[]{pkg});
    }

    public List<RouteCandidate> getRoutes(String pkg) {
        List<RouteCandidate> result = new ArrayList<>();
        Map<String, RouteCandidate> byKey = new HashMap<>();
        try (Cursor c = db().query(DbContract.TABLE_ROUTES, null,
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null,
                DbContract.COL_ORIGIN + " DESC, "
                        + DbContract.COL_ACTIVITY + " COLLATE NOCASE ASC, "
                        + DbContract.COL_ACTION + " ASC, " + DbContract.COL_URI + " ASC")) {
            while (c.moveToNext()) {
                RouteCandidate route = readRoute(c);
                result.add(route);
                byKey.put(route.routeKey(), route);
            }
        }
        if (result.isEmpty()) return result;

        try (Cursor c = db().query(DbContract.TABLE_ROUTE_TESTS, null,
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null, null)) {
            while (c.moveToNext()) {
                RouteCandidate route = byKey.get(
                        str(c, DbContract.COL_ROUTE_KEY));
                if (route == null) continue;
                route.resolveState = (int) num(c, DbContract.COL_RESOLVE_STATE);
                route.resolvedComponent = str(c, DbContract.COL_RESOLVED_COMPONENT);
                route.resolvedAction = str(c, DbContract.COL_RESOLVED_ACTION);
                route.testResult = (int) num(c, DbContract.COL_TEST_RESULT);
                route.testedAt = num(c, DbContract.COL_TESTED_AT);
                route.testedComponent = str(c, DbContract.COL_TESTED_COMPONENT);
            }
        }
        return result;
    }

    /**
     * Stores the resolve outcome only. Test fields are left untouched so a
     * rescan does not erase a user's verification.
     */
    public void saveRouteResolutions(String pkg, List<RouteCandidate> routes) {
        if (routes == null || routes.isEmpty()) return;
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (RouteCandidate route : routes) {
                if (!"dex".equals(route.origin)) continue;
                ContentValues values = new ContentValues();
                values.put(DbContract.COL_RESOLVE_STATE, route.resolveState);
                values.put(DbContract.COL_RESOLVED_COMPONENT, route.resolvedComponent);
                values.put(DbContract.COL_RESOLVED_ACTION, route.resolvedAction);
                int rows = db.update(DbContract.TABLE_ROUTE_TESTS, values,
                        DbContract.COL_PKG + "=? AND " + DbContract.COL_ROUTE_KEY + "=?",
                        new String[]{pkg, route.routeKey()});
                if (rows == 0) {
                    ContentValues insert = new ContentValues(values);
                    insert.put(DbContract.COL_PKG, pkg);
                    insert.put(DbContract.COL_ROUTE_KEY, route.routeKey());
                    insert.put(DbContract.COL_TEST_RESULT, RouteCandidate.TEST_NONE);
                    insert.put(DbContract.COL_TESTED_AT, 0);
                    db.insertWithOnConflict(DbContract.TABLE_ROUTE_TESTS, null, insert,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void updateRouteTest(String pkg, String routeKey, int testResult,
            long testedAt, String testedComponent) {
        ContentValues values = new ContentValues();
        values.put(DbContract.COL_TEST_RESULT, testResult);
        values.put(DbContract.COL_TESTED_AT, testedAt);
        values.put(DbContract.COL_TESTED_COMPONENT, testedComponent);
        int rows = db().update(DbContract.TABLE_ROUTE_TESTS, values,
                DbContract.COL_PKG + "=? AND " + DbContract.COL_ROUTE_KEY + "=?",
                new String[]{pkg, routeKey});
        if (rows == 0) {
            values.put(DbContract.COL_PKG, pkg);
            values.put(DbContract.COL_ROUTE_KEY, routeKey);
            db().insertWithOnConflict(DbContract.TABLE_ROUTE_TESTS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public void clearRouteTests(String pkg) {
        db().delete(DbContract.TABLE_ROUTE_TESTS,
                DbContract.COL_PKG + "=?", new String[]{pkg});
    }

    public int getRouteCount(String pkg) {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM " + DbContract.TABLE_ROUTES
                + " WHERE " + DbContract.COL_PKG + "=?", new String[]{pkg})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ── Captures ──

    public long insertCapture(CaptureEntry entry) {
        return db().insertOrThrow(DbContract.TABLE_CAPTURES, null, captureValues(entry));
    }

    public void insertCaptures(List<CaptureEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (CaptureEntry entry : entries) {
                db.insert(DbContract.TABLE_CAPTURES, null, captureValues(entry));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Keeps the newest {@code keep} captures for one package. */
    public void pruneCaptures(String pkg, int keep) {
        db().execSQL("DELETE FROM " + DbContract.TABLE_CAPTURES
                        + " WHERE " + DbContract.COL_PKG + "=? AND " + DbContract.COL_ID
                        + " NOT IN (SELECT " + DbContract.COL_ID + " FROM "
                        + DbContract.TABLE_CAPTURES + " WHERE " + DbContract.COL_PKG
                        + "=? ORDER BY " + DbContract.COL_TS + " DESC, " + DbContract.COL_ID
                        + " DESC LIMIT ?)",
                new Object[]{pkg, pkg, keep});
    }

    public List<CaptureEntry> getCaptures(String pkg, int limit) {
        List<CaptureEntry> result = new ArrayList<>();
        try (Cursor c = db().query(DbContract.TABLE_CAPTURES, null,
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null,
                DbContract.COL_TS + " DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                result.add(readCapture(c));
            }
        }
        return result;
    }

    public int countCaptures(String pkg) {
        String selection = pkg == null ? null : DbContract.COL_PKG + "=?";
        String[] args = pkg == null ? null : new String[]{pkg};
        try (Cursor c = db().query(DbContract.TABLE_CAPTURES,
                new String[]{"COUNT(*)"}, selection, args, null, null, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clearCaptures(String pkg) {
        db().delete(DbContract.TABLE_CAPTURES,
                DbContract.COL_PKG + "=?", new String[]{pkg});
    }

    public void clearAllCaptures() {
        db().delete(DbContract.TABLE_CAPTURES, null, null);
    }

    // ── Connections ──

    public void upsertConnection(String pkg, String process, int pid, long lastSeen,
            String moduleVersion) {
        ContentValues values = new ContentValues();
        values.put(DbContract.COL_PKG, pkg);
        values.put(DbContract.COL_PROCESS, process);
        values.put(DbContract.COL_PID, pid);
        values.put(DbContract.COL_LAST_SEEN, lastSeen);
        values.put(DbContract.COL_MODULE_VERSION, moduleVersion);
        db().insertWithOnConflict(DbContract.TABLE_CONNECTIONS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public ConnectionEntry getLatestConnection(String pkg) {
        try (Cursor c = db().query(DbContract.TABLE_CONNECTIONS, null,
                DbContract.COL_PKG + "=?", new String[]{pkg}, null, null,
                DbContract.COL_LAST_SEEN + " DESC", "1")) {
            return c.moveToFirst() ? readConnection(c) : null;
        }
    }

    /** Latest heartbeat per package, used by the app list. */
    public Map<String, ConnectionEntry> getAllConnections() {
        Map<String, ConnectionEntry> result = new HashMap<>();
        try (Cursor c = db().query(DbContract.TABLE_CONNECTIONS, null, null, null,
                null, null, DbContract.COL_LAST_SEEN + " DESC")) {
            while (c.moveToNext()) {
                ConnectionEntry entry = readConnection(c);
                if (entry.pkg != null && !result.containsKey(entry.pkg)) {
                    result.put(entry.pkg, entry);
                }
            }
        }
        return result;
    }

    // ── Row mappers ──

    private static ContentValues captureValues(CaptureEntry entry) {
        ContentValues values = new ContentValues();
        values.put(DbContract.COL_PKG, entry.pkg);
        values.put(DbContract.COL_PROCESS, entry.process);
        values.put(DbContract.COL_PID, entry.pid);
        values.put(DbContract.COL_SOURCE, entry.source);
        values.put(DbContract.COL_TS, entry.timestamp);
        values.put(DbContract.COL_TARGET_ACTIVITY, entry.targetActivity);
        values.put(DbContract.COL_TARGET_PKG, entry.targetPkg);
        values.put(DbContract.COL_CALLER, entry.caller);
        values.put(DbContract.COL_ACTION, entry.action);
        values.put(DbContract.COL_DATA, entry.data);
        values.put(DbContract.COL_TYPE, entry.type);
        values.put(DbContract.COL_CATEGORIES, entry.categories);
        values.put(DbContract.COL_FLAGS, entry.flags);
        values.put(DbContract.COL_EXTRAS_JSON, entry.extrasJson);
        return values;
    }

    private static ContentValues appValues(AppEntry app) {
        ContentValues v = new ContentValues();
        v.put(DbContract.COL_PKG, app.pkg);
        v.put(DbContract.COL_LABEL, app.label == null ? app.pkg : app.label);
        v.put(DbContract.COL_VERSION_CODE, app.versionCode);
        v.put(DbContract.COL_LAST_UPDATE_TIME, app.lastUpdateTime);
        v.put(DbContract.COL_APK_PATH, app.apkPath);
        v.put(DbContract.COL_APK_MTIME, app.apkMtime);
        v.put(DbContract.COL_TARGET_SDK, app.targetSdk);
        v.put(DbContract.COL_IS_SYSTEM, app.system ? 1 : 0);
        return v;
    }

    private static ContentValues routeValues(RouteCandidate route) {
        ContentValues v = new ContentValues();
        v.put(DbContract.COL_PKG, route.pkg);
        v.put(DbContract.COL_ACTIVITY, route.activity);
        v.put(DbContract.COL_ALIAS, route.alias);
        v.put(DbContract.COL_EXPORTED, route.exported ? 1 : 0);
        v.put(DbContract.COL_EXPORTED_INFERRED, route.exportedInferred ? 1 : 0);
        v.put(DbContract.COL_COMPONENT_ENABLED, route.componentEnabled ? 1 : 0);
        v.put(DbContract.COL_LAUNCH_MODE, route.launchMode);
        v.put(DbContract.COL_PERMISSION, route.permission);
        v.put(DbContract.COL_ACTION, route.action);
        v.put(DbContract.COL_CATEGORIES, route.categories);
        v.put(DbContract.COL_SCHEME, route.scheme);
        v.put(DbContract.COL_HOST, route.host);
        v.put(DbContract.COL_PORT, route.port);
        v.put(DbContract.COL_PATH, route.path);
        v.put(DbContract.COL_PATH_PREFIX, route.pathPrefix);
        v.put(DbContract.COL_PATH_PATTERN, route.pathPattern);
        v.put(DbContract.COL_PATH_SUFFIX, route.pathSuffix);
        v.put(DbContract.COL_MIME_TYPE, route.mimeType);
        v.put(DbContract.COL_URI, route.uri);
        v.put(DbContract.COL_PATTERN_ONLY, route.patternOnly ? 1 : 0);
        v.put(DbContract.COL_FILTER_INDEX, route.filterIndex);
        v.put(DbContract.COL_ORIGIN, route.origin == null ? "manifest" : route.origin);
        return v;
    }

    private static AppEntry readApp(Cursor c) {
        AppEntry a = new AppEntry();
        a.pkg = str(c, DbContract.COL_PKG);
        a.label = str(c, DbContract.COL_LABEL);
        a.versionCode = num(c, DbContract.COL_VERSION_CODE);
        a.lastUpdateTime = num(c, DbContract.COL_LAST_UPDATE_TIME);
        a.apkPath = str(c, DbContract.COL_APK_PATH);
        a.apkMtime = num(c, DbContract.COL_APK_MTIME);
        a.targetSdk = (int) num(c, DbContract.COL_TARGET_SDK);
        a.system = num(c, DbContract.COL_IS_SYSTEM) != 0;
        a.enabled = num(c, DbContract.COL_ENABLED) != 0;
        a.lastScanAt = num(c, DbContract.COL_LAST_SCAN_AT);
        a.scanError = str(c, DbContract.COL_SCAN_ERROR);
        return a;
    }

    private static RouteCandidate readRoute(Cursor c) {
        RouteCandidate r = new RouteCandidate();
        r.id = num(c, DbContract.COL_ID);
        r.pkg = str(c, DbContract.COL_PKG);
        r.activity = str(c, DbContract.COL_ACTIVITY);
        r.alias = str(c, DbContract.COL_ALIAS);
        r.exported = num(c, DbContract.COL_EXPORTED) != 0;
        r.exportedInferred = num(c, DbContract.COL_EXPORTED_INFERRED) != 0;
        r.componentEnabled = num(c, DbContract.COL_COMPONENT_ENABLED) != 0;
        r.launchMode = str(c, DbContract.COL_LAUNCH_MODE);
        r.permission = str(c, DbContract.COL_PERMISSION);
        r.action = str(c, DbContract.COL_ACTION);
        r.categories = str(c, DbContract.COL_CATEGORIES);
        r.scheme = str(c, DbContract.COL_SCHEME);
        r.host = str(c, DbContract.COL_HOST);
        r.port = str(c, DbContract.COL_PORT);
        r.path = str(c, DbContract.COL_PATH);
        r.pathPrefix = str(c, DbContract.COL_PATH_PREFIX);
        r.pathPattern = str(c, DbContract.COL_PATH_PATTERN);
        r.pathSuffix = str(c, DbContract.COL_PATH_SUFFIX);
        r.mimeType = str(c, DbContract.COL_MIME_TYPE);
        r.uri = str(c, DbContract.COL_URI);
        r.patternOnly = num(c, DbContract.COL_PATTERN_ONLY) != 0;
        r.filterIndex = (int) num(c, DbContract.COL_FILTER_INDEX);
        String origin = str(c, DbContract.COL_ORIGIN);
        r.origin = origin == null ? "manifest" : origin;
        return r;
    }

    private static CaptureEntry readCapture(Cursor c) {
        CaptureEntry e = new CaptureEntry();
        e.id = num(c, DbContract.COL_ID);
        e.pkg = str(c, DbContract.COL_PKG);
        e.process = str(c, DbContract.COL_PROCESS);
        e.pid = (int) num(c, DbContract.COL_PID);
        e.source = str(c, DbContract.COL_SOURCE);
        e.timestamp = num(c, DbContract.COL_TS);
        e.targetActivity = str(c, DbContract.COL_TARGET_ACTIVITY);
        e.targetPkg = str(c, DbContract.COL_TARGET_PKG);
        e.caller = str(c, DbContract.COL_CALLER);
        e.action = str(c, DbContract.COL_ACTION);
        e.data = str(c, DbContract.COL_DATA);
        e.type = str(c, DbContract.COL_TYPE);
        e.categories = str(c, DbContract.COL_CATEGORIES);
        e.flags = (int) num(c, DbContract.COL_FLAGS);
        e.extrasJson = str(c, DbContract.COL_EXTRAS_JSON);
        return e;
    }

    private static ConnectionEntry readConnection(Cursor c) {
        ConnectionEntry e = new ConnectionEntry();
        e.pkg = str(c, DbContract.COL_PKG);
        e.process = str(c, DbContract.COL_PROCESS);
        e.pid = (int) num(c, DbContract.COL_PID);
        e.lastSeen = num(c, DbContract.COL_LAST_SEEN);
        e.moduleVersion = str(c, DbContract.COL_MODULE_VERSION);
        return e;
    }

    private static String str(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index < 0 || c.isNull(index) ? null : c.getString(index);
    }

    private static long num(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index < 0 || c.isNull(index) ? 0L : c.getLong(index);
    }
}

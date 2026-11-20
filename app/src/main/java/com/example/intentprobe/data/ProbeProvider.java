package com.example.intentprobe.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.intentprobe.capture.ProbeProtocol;
import com.example.intentprobe.model.CaptureEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-process ingress for the Xposed capture path.
 *
 * <p>Only {@link #insert} is implemented. The app reads the database directly
 * through {@link ProbeRepository}; external callers never get query access.
 * Access is checked against the caller uid's packages, and events additionally
 * require the user to have enabled probing for that package.</p>
 */
public class ProbeProvider extends ContentProvider {

    public static final String AUTHORITY = ProbeProtocol.AUTHORITY;
    public static final String PATH_EVENTS = ProbeProtocol.PATH_EVENTS;
    public static final String PATH_HELLO = ProbeProtocol.PATH_HELLO;

    public static final Uri URI_EVENTS = Uri.parse(ProbeProtocol.URI_EVENTS);
    public static final Uri URI_HELLO = Uri.parse(ProbeProtocol.URI_HELLO);

    private static final long UID_CACHE_MS = 60 * 1000L;
    private static final long HELLO_THROTTLE_MS = 5 * 1000L;

    private static final Map<Integer, CachedPackages> UID_CACHE = new HashMap<>();
    private static final Map<String, Long> LAST_HELLO = new HashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        if (values == null) return null;
        Context context = getContext();
        if (context == null) return null;
        String path = uri.getLastPathSegment();
        if (path == null) return null;

        int uid = Binder.getCallingUid();
        String[] callerPackages = packagesForUid(context, uid);
        if (callerPackages == null || callerPackages.length == 0) return null;

        if (PATH_HELLO.equals(path)) {
            return insertHello(context, values, callerPackages);
        }
        if (PATH_EVENTS.equals(path)) {
            return insertEvents(context, values, callerPackages);
        }
        return null;
    }

    private Uri insertHello(Context context, ContentValues values, String[] callerPackages) {
        String pkg = trim(values.getAsString("pkg"), 256);
        if (pkg == null || !contains(callerPackages, pkg)) return null;

        String process = trim(values.getAsString("process"), 256);
        if (process == null) process = pkg;
        Integer pidValue = values.getAsInteger("pid");
        int pid = pidValue == null ? 0 : pidValue;
        String moduleVersion = trim(values.getAsString("module_version"), 64);
        long now = System.currentTimeMillis();

        synchronized (LAST_HELLO) {
            String key = pkg + '|' + process;
            Long previous = LAST_HELLO.get(key);
            if (previous != null && now - previous < HELLO_THROTTLE_MS) {
                return URI_HELLO;
            }
            LAST_HELLO.put(key, now);
        }

        ProbeRepository repository = new ProbeRepository(context);
        repository.upsertConnection(pkg, process, pid, now, moduleVersion);
        context.getContentResolver().notifyChange(URI_HELLO, null);
        return URI_HELLO;
    }

    private Uri insertEvents(Context context, ContentValues values, String[] callerPackages) {
        String pkg = trim(values.getAsString("pkg"), 256);
        if (pkg == null || !contains(callerPackages, pkg)) return null;

        ProbeRepository repository = new ProbeRepository(context);
        if (!repository.isPackageEnabled(pkg)) {
            return null;
        }

        String process = trim(values.getAsString("process"), 256);
        if (process == null) process = pkg;
        Integer pidValue = values.getAsInteger("pid");
        int pid = pidValue == null ? 0 : pidValue;

        String batch = values.getAsString("batch");
        if (batch == null || batch.isEmpty()
                || batch.length() > ProbeProtocol.MAX_BATCH_BYTES) {
            return null;
        }

        JSONArray array;
        try {
            array = new JSONArray(batch);
        } catch (Throwable t) {
            return null;
        }

        long now = System.currentTimeMillis();
        int count = Math.min(array.length(), ProbeProtocol.MAX_BATCH_EVENTS);
        List<CaptureEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            CaptureEntry entry = new CaptureEntry();
            entry.pkg = pkg;
            entry.process = process;
            entry.pid = pid;
            entry.source = source(object.optString("src", ""));
            long timestamp = object.optLong("ts", now);
            if (timestamp <= 0 || timestamp > now + 5 * 60 * 1000L) timestamp = now;
            entry.timestamp = timestamp;
            entry.targetActivity = trim(object.optString("target", null), 512);
            entry.targetPkg = trim(object.optString("tpkg", null), 256);
            entry.caller = trim(object.optString("caller", null), 512);
            entry.action = trim(object.optString("action", null), 512);
            entry.data = trim(object.optString("data", null), 4096);
            entry.type = trim(object.optString("type", null), 256);
            entry.categories = trim(object.optString("cat", null), 1024);
            entry.flags = object.optInt("flags", 0);
            String extras = object.optString("extras", null);
            if (extras != null && extras.length() > ProbeProtocol.MAX_EXTRAS_JSON_CHARS) {
                extras = null;
            }
            entry.extrasJson = extras;
            entries.add(entry);
        }
        if (entries.isEmpty()) return null;

        repository.insertCaptures(entries);
        repository.pruneCaptures(pkg, ProbeProtocol.MAX_CAPTURES_PER_PACKAGE);
        context.getContentResolver().notifyChange(URI_EVENTS, null);
        return URI_EVENTS;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("ProbeProvider is append-only");
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("ProbeProvider is append-only");
    }

    private static String source(String value) {
        if (ProbeProtocol.SOURCE_ON_CREATE.equals(value)) return value;
        if (ProbeProtocol.SOURCE_ON_NEW_INTENT.equals(value)) return value;
        if (ProbeProtocol.SOURCE_OUTGOING.equals(value)) return value;
        return "unknown";
    }

    private static String[] packagesForUid(Context context, int uid) {
        if (uid == Process.myUid()) {
            return new String[]{context.getPackageName()};
        }
        synchronized (UID_CACHE) {
            CachedPackages cached = UID_CACHE.get(uid);
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.at < UID_CACHE_MS) {
                return cached.packages;
            }
            String[] packages = null;
            try {
                packages = context.getPackageManager().getPackagesForUid(uid);
            } catch (Throwable ignored) {
            }
            UID_CACHE.put(uid, new CachedPackages(now, packages));
            return packages;
        }
    }

    private static boolean contains(String[] values, String value) {
        if (values == null) return false;
        for (String item : values) {
            if (value.equals(item)) return true;
        }
        return false;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static final class CachedPackages {
        final long at;
        final String[] packages;

        CachedPackages(long at, String[] packages) {
            this.at = at;
            this.packages = packages;
        }
    }
}

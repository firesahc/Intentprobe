package com.example.intentprobe.capture;

import android.os.Bundle;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared wire format between the Xposed hook (inside the target app) and the
 * probe app. Kept free of UI and database types so the target process only
 * loads the small capture classes it needs.
 */
public final class ProbeProtocol {

    public static final String AUTHORITY = "com.example.intentprobe.provider";
    public static final String PATH_EVENTS = "events";
    public static final String PATH_HELLO = "hello";
    public static final String URI_EVENTS = "content://" + AUTHORITY + "/" + PATH_EVENTS;
    public static final String URI_HELLO = "content://" + AUTHORITY + "/" + PATH_HELLO;

    public static final String MODULE_VERSION = "0.1";

    /** Hard caps mirrored by the provider so a buggy caller cannot flood it. */
    public static final int MAX_BATCH_EVENTS = 20;
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    public static final int MAX_EXTRAS = 40;
    public static final int MAX_VALUE_CHARS = 512;
    public static final int MAX_EXTRAS_JSON_CHARS = 16 * 1024;
    public static final int MAX_CAPTURES_PER_PACKAGE = 500;

    public static final long HEARTBEAT_MS = 5 * 60 * 1000L;
    /** UI treats a connection as live for a little longer than one heartbeat. */
    public static final long CONNECTION_FRESH_MS = HEARTBEAT_MS + 60 * 1000L;

    public static final String SOURCE_ON_CREATE = "onCreate";
    public static final String SOURCE_ON_NEW_INTENT = "onNewIntent";
    /** An Intent the target app sent out through Instrumentation.execStartActivity. */
    public static final String SOURCE_OUTGOING = "outgoing";

    private static final Set<String> SENSITIVE_PARTS = new HashSet<>(Arrays.asList(
            "token", "password", "passwd", "secret", "session", "cookie",
            "authorization", "credential", "ticket", "signature",
            "accesskey", "access_key", "refreshkey", "refresh_key"
    ));

    private static final Set<String> SENSITIVE_EXACT = new HashSet<>(Arrays.asList(
            "pwd", "auth", "sign", "key", "sid"
    ));

    private static final Set<String> URI_EXTRA_TYPES = new HashSet<>(Arrays.asList(
            "String", "int", "long", "boolean"
    ));

    private ProbeProtocol() {}

    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        if (SENSITIVE_EXACT.contains(normalized)) return true;
        for (String part : SENSITIVE_PARTS) {
            if (normalized.contains(part)) return true;
        }
        return false;
    }

    public static boolean isUriExtraType(String type) {
        return type != null && URI_EXTRA_TYPES.contains(type);
    }

    /**
     * Reads a Bundle without touching values that are not basic types. The
     * returned JSON is {@code [{"k":..,"t":..,"v":..,"tr":1}]}; sensitive keys
     * and non-basic values carry no {@code v}.
     */
    public static String readExtras(Bundle extras) {
        if (extras == null) return null;
        JSONArray array = new JSONArray();
        int truncated = 0;
        int total = 0;
        try {
            for (String key : extras.keySet()) {
                if (key == null) continue;
                total++;
                if (total > MAX_EXTRAS) {
                    truncated++;
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("k", key);
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable t) {
                    item.put("t", "unreadable");
                    array.put(item);
                    continue;
                }
                String type = typeOf(value);
                item.put("t", type);
                if (value != null && isUriExtraType(type) && !isSensitiveKey(key)) {
                    String text = String.valueOf(value);
                    if (text.length() > MAX_VALUE_CHARS) {
                        item.put("v", text.substring(0, MAX_VALUE_CHARS));
                        item.put("tr", 1);
                    } else {
                        item.put("v", text);
                    }
                }
                array.put(item);
                if (array.toString().length() > MAX_EXTRAS_JSON_CHARS) {
                    array.remove(array.length() - 1);
                    truncated++;
                    break;
                }
            }
            if (truncated > 0) {
                JSONObject marker = new JSONObject();
                marker.put("k", "");
                marker.put("t", "__truncated__");
                marker.put("v", String.valueOf(truncated));
                array.put(marker);
            }
        } catch (Throwable t) {
            // A broken Bundle must never crash the target app.
            try {
                JSONObject marker = new JSONObject();
                marker.put("k", "");
                marker.put("t", "__error__");
                marker.put("v", t.getClass().getSimpleName());
                array.put(marker);
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    public static String typeOf(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "String";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Float) return "float";
        if (value instanceof Double) return "double";
        if (value instanceof Short) return "short";
        if (value instanceof Byte) return "byte";
        if (value instanceof Character) return "char";
        if (value instanceof CharSequence) return "String";
        if (value instanceof Bundle) return "Bundle";
        return value.getClass().getName();
    }

    public static final class ExtraItem {
        public String key;
        public String type;
        public String value;
        public boolean truncated;
    }

    public static final class ExtrasInfo {
        public int total;
        public int hidden;
        public int unsupported;
        public int truncated;
        public int uriReady;
    }

    public static List<ExtraItem> parseExtras(String json) {
        List<ExtraItem> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                ExtraItem item = new ExtraItem();
                item.key = object.optString("k", "");
                item.type = object.optString("t", "");
                if (object.has("v") && !object.isNull("v")) {
                    item.value = object.optString("v", null);
                }
                item.truncated = object.optInt("tr", 0) != 0;
                result.add(item);
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    public static ExtrasInfo summarize(String json) {
        ExtrasInfo info = new ExtrasInfo();
        for (ExtraItem item : parseExtras(json)) {
            if ("__truncated__".equals(item.type)) {
                try {
                    info.truncated += Integer.parseInt(item.value == null ? "0" : item.value);
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if ("__error__".equals(item.type)) continue;
            info.total++;
            if (item.value == null) {
                if (isUriExtraType(item.type)) {
                    info.hidden++;
                } else {
                    info.unsupported++;
                }
            } else if (!isUriExtraType(item.type) || item.truncated) {
                info.unsupported++;
            } else {
                info.uriReady++;
            }
        }
        return info;
    }

    /** First non-sensitive extra whose value looks like a URI, used for previews. */
    public static ExtraItem firstUriExtra(String json) {
        for (ExtraItem item : parseExtras(json)) {
            if (item.value == null || item.truncated) continue;
            if (item.value.contains("://")) return item;
        }
        return null;
    }

    public static List<ExtraItem> uriExtras(String json) {
        List<ExtraItem> result = new ArrayList<>();
        for (ExtraItem item : parseExtras(json)) {
            if (item.value == null || item.truncated) continue;
            if (item.value.contains("://")) result.add(item);
        }
        return result;
    }

    public static String currentProcessName(String fallback) {
        try {
            String name = android.app.Application.getProcessName();
            return name == null ? fallback : name;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static int currentPid() {
        try {
            return Process.myPid();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}

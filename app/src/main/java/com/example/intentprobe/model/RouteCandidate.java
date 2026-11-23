package com.example.intentprobe.model;

public class RouteCandidate {
    public static final int RESOLVE_UNKNOWN = 0;
    public static final int RESOLVE_MATCHED = 1;
    public static final int RESOLVE_NONE = 2;
    public static final int RESOLVE_ERROR = 3;
    public static final int RESOLVE_BLOCKED = 4;

    public static final int TEST_NONE = 0;
    public static final int TEST_STARTED = 1;
    public static final int TEST_CAPTURED = 2;
    public static final int TEST_FAILED = 3;
    public static final int TEST_NOT_CAPTURED = 4;

    public long id;
    public String pkg;
    public String activity;
    public String alias;
    public boolean exported;
    public boolean exportedInferred;
    public boolean componentEnabled = true;
    public String launchMode;
    public String permission;
    public String action;
    public String categories;
    public String scheme;
    public String host;
    public String port;
    public String path;
    public String pathPrefix;
    public String pathPattern;
    public String pathSuffix;
    public String mimeType;
    public String uri;
    public boolean patternOnly;
    public int filterIndex;
    /** "manifest" for intent-filter entries, "dex" for string constants in code. */
    public String origin = "manifest";

    // Joined from route_tests; not part of the scanned route definition.
    public int resolveState;
    public String resolvedComponent;
    public String resolvedAction;
    public int testResult;
    public long testedAt;
    public String testedComponent;

    /** Stable across rescans, unlike the auto-increment id. */
    public String routeKey() {
        String kind = origin == null ? "manifest" : origin;
        String component = activity == null ? "" : activity;
        String data = uri == null ? "" : uri;
        return kind + "|" + component + "#" + filterIndex + "|" + data;
    }

    public String simpleActivity() {
        if (activity == null) return "";
        int dot = activity.lastIndexOf('.');
        return dot >= 0 ? activity.substring(dot + 1) : activity;
    }
}

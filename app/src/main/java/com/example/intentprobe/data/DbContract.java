package com.example.intentprobe.data;

/** Single place for SQLite table and column names. */
public final class DbContract {

    private DbContract() {}

    public static final String TABLE_APPS = "apps";
    public static final String TABLE_ROUTES = "routes";
    public static final String TABLE_CAPTURES = "captures";
    public static final String TABLE_CONNECTIONS = "connections";

    public static final String COL_ID = "_id";
    public static final String COL_PKG = "pkg";
    public static final String COL_LABEL = "label";
    public static final String COL_VERSION_CODE = "version_code";
    public static final String COL_LAST_UPDATE_TIME = "last_update_time";
    public static final String COL_APK_PATH = "apk_path";
    public static final String COL_APK_MTIME = "apk_mtime";
    public static final String COL_TARGET_SDK = "target_sdk";
    public static final String COL_IS_SYSTEM = "is_system";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_LAST_SCAN_AT = "last_scan_at";
    public static final String COL_SCAN_ERROR = "scan_error";

    public static final String COL_ACTIVITY = "activity";
    public static final String COL_ALIAS = "alias";
    public static final String COL_EXPORTED = "exported";
    public static final String COL_EXPORTED_INFERRED = "exported_inferred";
    public static final String COL_COMPONENT_ENABLED = "component_enabled";
    public static final String COL_LAUNCH_MODE = "launch_mode";
    public static final String COL_PERMISSION = "permission";
    public static final String COL_ACTION = "action";
    public static final String COL_CATEGORIES = "categories";
    public static final String COL_SCHEME = "scheme";
    public static final String COL_HOST = "host";
    public static final String COL_PORT = "port";
    public static final String COL_PATH = "path";
    public static final String COL_PATH_PREFIX = "path_prefix";
    public static final String COL_PATH_PATTERN = "path_pattern";
    public static final String COL_PATH_SUFFIX = "path_suffix";
    public static final String COL_MIME_TYPE = "mime_type";
    public static final String COL_URI = "uri";
    public static final String COL_PATTERN_ONLY = "pattern_only";
    public static final String COL_FILTER_INDEX = "filter_index";
    public static final String COL_ORIGIN = "origin";

    public static final String COL_PROCESS = "process";
    public static final String COL_PID = "pid";
    public static final String COL_SOURCE = "source";
    public static final String COL_TS = "ts";
    public static final String COL_TARGET_ACTIVITY = "target_activity";
    public static final String COL_TARGET_PKG = "target_pkg";
    public static final String COL_CALLER = "caller";
    public static final String COL_DATA = "data";
    public static final String COL_TYPE = "type";
    public static final String COL_FLAGS = "flags";
    public static final String COL_EXTRAS_JSON = "extras_json";

    // Route test state is kept apart from the scanned route rows: a rescan
    // rebuilds routes, but a user's test result must survive it.
    public static final String TABLE_ROUTE_TESTS = "route_tests";
    public static final String COL_ROUTE_KEY = "route_key";
    public static final String COL_RESOLVE_STATE = "resolve_state";
    public static final String COL_RESOLVED_COMPONENT = "resolved_component";
    public static final String COL_RESOLVED_ACTION = "resolved_action";
    public static final String COL_TEST_RESULT = "test_result";
    public static final String COL_TESTED_AT = "tested_at";
    public static final String COL_TESTED_COMPONENT = "tested_component";

    public static final String COL_LAST_SEEN = "last_seen";
    public static final String COL_MODULE_VERSION = "module_version";
}

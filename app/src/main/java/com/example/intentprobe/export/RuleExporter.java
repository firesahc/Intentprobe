package com.example.intentprobe.export;

import android.content.Intent;
import android.net.Uri;

import com.example.intentprobe.capture.ProbeProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds rule strings that launchRedirector accepts.
 *
 * <p>This mirrors the real parser instead of guessing: an {@code intent:} URI
 * is produced by {@link Intent#toUri(int)}, then checked against the same outer
 * limits. Pure URIs and full class names are checked against the corresponding
 * {@code RedirectRule} patterns.</p>
 */
public final class RuleExporter {

    public static final int MAX_LENGTH = 8192;
    public static final int MAX_EXTRAS = 20;

    private static final String PREFIX = "intent:";
    private static final String SUFFIX = ";end";
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]");
    private static final Pattern URI_SCHEME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:[^\\s]+$");
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\.[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*"
                    + "|[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*");

    private static final Set<String> BLOCKED_SCHEMES = new HashSet<>(Arrays.asList(
            "javascript", "file", "content", "intent"
    ));

    private static final Set<String> IGNORED_CATEGORIES = new HashSet<>(Arrays.asList(
            "android.intent.category.DEFAULT"
    ));

    private static final Set<String> PURE_URI_CATEGORIES = new HashSet<>(Arrays.asList(
            "android.intent.category.DEFAULT",
            "android.intent.category.BROWSABLE"
    ));

    public enum Error {
        NONE,
        TOO_LONG,
        TOO_MANY_EXTRAS,
        BLOCKED_SCHEME,
        UNSUPPORTED,
        INVALID
    }

    public static final class Outcome {
        public String value;
        public Error error = Error.NONE;
        public String detail;
        public int droppedExtras;
        public boolean componentUsed;

        public boolean ok() {
            return error == Error.NONE && value != null;
        }

        static Outcome failure(Error error, String detail) {
            Outcome outcome = new Outcome();
            outcome.error = error;
            outcome.detail = detail;
            return outcome;
        }
    }

    public static final class Extra {
        public final String key;
        public final String type;
        public final String value;

        public Extra(String key, String type, String value) {
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    private RuleExporter() {}

    public static Outcome buildIntentUri(String pkg, String action, String data, String type,
            String categories, String componentClass, List<Extra> extras) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return Outcome.failure(Error.INVALID, "缺少包名");
        }
        if (!isSchemeAllowed(data)) {
            return Outcome.failure(Error.BLOCKED_SCHEME, null);
        }

        Intent intent = new Intent();
        if (!isEmpty(action)) {
            intent.setAction(action);
        } else if (!isEmpty(data) || !isEmpty(type)) {
            intent.setAction(Intent.ACTION_VIEW);
        }

        if (!isEmpty(data) && !isEmpty(type)) {
            intent.setDataAndType(Uri.parse(data), type);
        } else if (!isEmpty(data)) {
            intent.setData(Uri.parse(data));
        } else if (!isEmpty(type)) {
            intent.setType(type);
        }

        if (!isEmpty(componentClass)) {
            intent.setClassName(pkg, resolveClassName(pkg, componentClass));
        } else {
            intent.setPackage(pkg);
        }

        if (!isEmpty(categories)) {
            for (String category : categories.split(",")) {
                String trimmed = category.trim();
                if (!trimmed.isEmpty() && !IGNORED_CATEGORIES.contains(trimmed)) {
                    intent.addCategory(trimmed);
                }
            }
        }

        Outcome outcome = new Outcome();
        outcome.componentUsed = !isEmpty(componentClass);
        if (extras != null) {
            int included = 0;
            for (Extra extra : extras) {
                if (extra == null || isEmpty(extra.key)) continue;
                if (extra.value == null || !ProbeProtocol.isUriExtraType(extra.type)) {
                    outcome.droppedExtras++;
                    continue;
                }
                if (!putExtra(intent, extra)) {
                    outcome.droppedExtras++;
                    continue;
                }
                included++;
            }
            if (included > MAX_EXTRAS) {
                return Outcome.failure(Error.TOO_MANY_EXTRAS, String.valueOf(included));
            }
        }

        String uri;
        try {
            uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        } catch (Throwable t) {
            return Outcome.failure(Error.INVALID, t.getClass().getSimpleName());
        }
        String error = validateIntentUri(uri);
        if (error != null) {
            return Outcome.failure(Error.INVALID, error);
        }
        outcome.value = uri;
        return outcome;
    }

    public static Outcome buildPureUri(String action, String data, String type,
            String categories) {
        if (isEmpty(data)) return Outcome.failure(Error.UNSUPPORTED, "缺少 data");
        if (!isSchemeAllowed(data)) return Outcome.failure(Error.BLOCKED_SCHEME, null);
        if (!isEmpty(type)) return Outcome.failure(Error.UNSUPPORTED, "带 MIME 类型时纯 URI 会丢失类型");
        if (!isEmpty(action) && !Intent.ACTION_VIEW.equals(action)) {
            return Outcome.failure(Error.UNSUPPORTED, "action 不是 VIEW");
        }
        if (!isEmpty(categories)) {
            for (String category : categories.split(",")) {
                String trimmed = category.trim();
                if (!trimmed.isEmpty() && !PURE_URI_CATEGORIES.contains(trimmed)) {
                    return Outcome.failure(Error.UNSUPPORTED, "包含纯 URI 无法携带的 category");
                }
            }
        }
        if (!URI_SCHEME_PATTERN.matcher(data).matches()) {
            return Outcome.failure(Error.INVALID, "URI 含空白或缺少 scheme");
        }
        Outcome outcome = new Outcome();
        outcome.value = data;
        return outcome;
    }

    public static Outcome buildClassName(String pkg, String activity) {
        if (isEmpty(pkg) || isEmpty(activity)) {
            return Outcome.failure(Error.UNSUPPORTED, "缺少 Activity 类名");
        }
        String full = resolveClassName(pkg, activity);
        if (!CLASS_NAME_PATTERN.matcher(full).matches()) {
            return Outcome.failure(Error.INVALID, "类名格式不合法");
        }
        Outcome outcome = new Outcome();
        outcome.value = full;
        outcome.componentUsed = true;
        return outcome;
    }

    public static boolean isSchemeAllowed(String data) {
        if (isEmpty(data)) return true;
        try {
            String scheme = Uri.parse(data).getScheme();
            return scheme == null
                    || !BLOCKED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    public static String schemeOf(String data) {
        if (isEmpty(data)) return null;
        try {
            return Uri.parse(data).getScheme();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean canUsePureUri(String action, String data, String type,
            String categories) {
        return buildPureUri(action, data, type, categories).ok();
    }

    public static List<Extra> fromCaptureExtras(String json) {
        List<Extra> result = new ArrayList<>();
        for (ProbeProtocol.ExtraItem item : ProbeProtocol.parseExtras(json)) {
            if ("__truncated__".equals(item.type) || "__error__".equals(item.type)) continue;
            if (item.value == null || item.truncated) continue;
            result.add(new Extra(item.key, item.type, item.value));
        }
        return result;
    }

    public static String resolveClassName(String pkg, String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith(".")) return pkg + trimmed;
        if (!trimmed.contains(".")) return pkg + "." + trimmed;
        return trimmed;
    }

    private static boolean putExtra(Intent intent, Extra extra) {
        String value = extra.value;
        try {
            switch (extra.type) {
                case "int":
                    intent.putExtra(extra.key, Integer.parseInt(value.trim()));
                    return true;
                case "long":
                    intent.putExtra(extra.key, Long.parseLong(value.trim()));
                    return true;
                case "boolean":
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    if (!"true".equals(normalized) && !"false".equals(normalized)) {
                        return false;
                    }
                    intent.putExtra(extra.key, Boolean.parseBoolean(normalized));
                    return true;
                case "String":
                    intent.putExtra(extra.key, value);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static String validateIntentUri(String value) {
        if (value == null) return "未生成 URI";
        if (!value.startsWith(PREFIX)) return "缺少 intent: 前缀";
        if (value.length() > MAX_LENGTH) return "长度 " + value.length() + " 超过 8192";
        if (!value.endsWith(SUFFIX)) return "缺少 ;end 后缀";
        if (CONTROL_CHARS.matcher(value).find()) return "包含控制字符";
        int marker = value.indexOf("#Intent;");
        if (marker < 0) return "缺少 #Intent;";
        if (value.substring(marker).contains(";data=")) return "data 位于 #Intent; 之后";
        if (value.length() <= PREFIX.length() + SUFFIX.length()) return "URI 为空";
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}

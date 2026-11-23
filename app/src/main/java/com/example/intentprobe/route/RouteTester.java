package com.example.intentprobe.route;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.example.intentprobe.export.RuleExporter;
import com.example.intentprobe.model.RouteCandidate;

import java.util.Locale;

/**
 * Launches one route with the same Intent that the exporter would produce.
 *
 * <p>This is a deliberate, user-triggered action. It never launches in bulk,
 * and callers must ask for confirmation when {@link #isHighRisk} is true.</p>
 */
public final class RouteTester {

    public enum Result {
        LAUNCHED,
        NO_MATCH,
        NOT_EXPORTED,
        SECURITY,
        FAILED
    }

    public static final class Outcome {
        public final Result result;
        public final String detail;
        public final String component;

        Outcome(Result result, String detail, String component) {
            this.result = result;
            this.detail = detail;
            this.component = component;
        }
    }

    private static final String[] RISK_WORDS = {
            "pay", "payment", "order", "buy", "recharge", "purchase",
            "logout", "delete", "clear", "reset", "unbind", "destroy", "exit"
    };

    private RouteTester() {
    }

    public static boolean isHighRisk(RouteCandidate route) {
        if (route == null) return false;
        String text = (route.uri == null ? "" : route.uri) + " "
                + (route.action == null ? "" : route.action);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : RISK_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    public static Outcome launch(Activity activity, RouteCandidate route) {
        if (activity == null || route == null || route.pkg == null) {
            return new Outcome(Result.FAILED, "缺少路由信息", null);
        }

        String action = route.resolvedAction != null && !route.resolvedAction.isEmpty()
                ? route.resolvedAction : route.action;
        String component = null;
        if (route.uri == null || route.uri.isEmpty()) {
            component = route.alias != null ? route.alias : route.activity;
        }

        Intent intent = RuleExporter.buildLaunchIntent(route.pkg, action, route.uri,
                route.mimeType, route.categories, component);
        if (intent == null) {
            return new Outcome(Result.FAILED, "Intent 无法构造", null);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PackageManager pm = activity.getPackageManager();
        ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null
                || !route.pkg.equals(resolved.activityInfo.packageName)) {
            return new Outcome(Result.NO_MATCH, null, null);
        }
        if (!resolved.activityInfo.exported) {
            return new Outcome(Result.NOT_EXPORTED, resolved.activityInfo.name,
                    resolved.activityInfo.name);
        }

        try {
            activity.startActivity(intent);
            return new Outcome(Result.LAUNCHED, null, resolved.activityInfo.name);
        } catch (ActivityNotFoundException e) {
            return new Outcome(Result.NO_MATCH, e.getMessage(), null);
        } catch (SecurityException e) {
            return new Outcome(Result.SECURITY, e.getMessage(), resolved.activityInfo.name);
        } catch (Throwable t) {
            return new Outcome(Result.FAILED, t.getClass().getSimpleName(),
                    resolved.activityInfo.name);
        }
    }
}

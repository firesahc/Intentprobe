package com.example.intentprobe.route;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import com.example.intentprobe.model.RouteCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks which dex routes the system can actually resolve.
 *
 * <p>Dex routes have no component and no real action. The scanner assumes
 * {@code ACTION_VIEW}; this resolver tries that first and then falls back to
 * the actions declared for the same scheme in the manifest. The result is
 * advisory: a resolved route may still land on a router page that finishes
 * immediately.</p>
 */
public final class RouteResolver {

    public interface Progress {
        void onProgress(int done, int total, int matched);
    }

    private RouteResolver() {
    }

    public static void resolveAll(Context context, String pkg, List<RouteCandidate> routes,
            Progress progress) {
        if (context == null || routes == null || routes.isEmpty()) return;
        PackageManager pm = context.getApplicationContext().getPackageManager();
        Map<String, Set<String>> actionsByScheme = collectActions(routes);

        List<RouteCandidate> dexRoutes = new ArrayList<>();
        for (RouteCandidate route : routes) {
            if ("dex".equals(route.origin) && route.uri != null && !route.uri.isEmpty()) {
                dexRoutes.add(route);
            }
        }

        int total = dexRoutes.size();
        int done = 0;
        int matched = 0;
        for (RouteCandidate route : dexRoutes) {
            if (Thread.currentThread().isInterrupted()) break;
            resolveOne(pm, pkg, route, actionsByScheme);
            if (route.resolveState == RouteCandidate.RESOLVE_MATCHED) matched++;
            done++;
            if (progress != null && (done == total || done % 25 == 0)) {
                progress.onProgress(done, total, matched);
            }
        }
        if (progress != null && done < total) {
            progress.onProgress(done, total, matched);
        }
    }

    private static void resolveOne(PackageManager pm, String pkg, RouteCandidate route,
            Map<String, Set<String>> actionsByScheme) {
        String scheme = route.scheme;
        if (scheme == null || scheme.isEmpty()) {
            scheme = schemeOf(route.uri);
        }

        Set<String> actions = new LinkedHashSet<>();
        actions.add(Intent.ACTION_VIEW);
        if (scheme != null) {
            Set<String> learned = actionsByScheme.get(scheme.toLowerCase(Locale.ROOT));
            if (learned != null) actions.addAll(learned);
        }

        String blockedComponent = null;
        String blockedAction = null;
        try {
            for (String action : actions) {
                Intent intent = new Intent(action, Uri.parse(route.uri));
                intent.setPackage(pkg);
                List<ResolveInfo> matches = pm.queryIntentActivities(intent,
                        PackageManager.MATCH_DEFAULT_ONLY);
                if (matches == null) continue;
                for (ResolveInfo info : matches) {
                    ActivityInfo activity = info.activityInfo;
                    if (activity == null || !pkg.equals(activity.packageName)) continue;
                    if (activity.exported) {
                        route.resolveState = RouteCandidate.RESOLVE_MATCHED;
                        route.resolvedComponent = activity.name;
                        route.resolvedAction = action;
                        return;
                    }
                    if (blockedComponent == null) {
                        blockedComponent = activity.name;
                        blockedAction = action;
                    }
                }
            }
            if (blockedComponent != null) {
                route.resolveState = RouteCandidate.RESOLVE_BLOCKED;
                route.resolvedComponent = blockedComponent;
                route.resolvedAction = blockedAction;
            } else {
                route.resolveState = RouteCandidate.RESOLVE_NONE;
                route.resolvedComponent = null;
                route.resolvedAction = null;
            }
        } catch (Throwable t) {
            route.resolveState = RouteCandidate.RESOLVE_ERROR;
            route.resolvedComponent = null;
            route.resolvedAction = null;
        }
    }

    private static Map<String, Set<String>> collectActions(List<RouteCandidate> routes) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (RouteCandidate route : routes) {
            if (!"manifest".equals(route.origin)) continue;
            String action = route.action;
            if (action == null || action.isEmpty() || Intent.ACTION_VIEW.equals(action)) {
                continue;
            }
            String scheme = route.scheme;
            if (scheme == null || scheme.isEmpty()) scheme = schemeOf(route.uri);
            if (scheme == null) continue;
            String key = scheme.toLowerCase(Locale.ROOT);
            Set<String> actions = result.get(key);
            if (actions == null) {
                actions = new LinkedHashSet<>();
                result.put(key, actions);
            }
            actions.add(action);
        }
        return result;
    }

    private static String schemeOf(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            return Uri.parse(uri).getScheme();
        } catch (Throwable t) {
            return null;
        }
    }
}

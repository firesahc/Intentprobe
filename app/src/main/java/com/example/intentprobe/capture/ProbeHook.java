package com.example.intentprobe.capture;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point. Read-only: it observes the Intents an Activity received
 * and the Intents the app sends through {@link Instrumentation#execStartActivity}.
 * A heartbeat proves the scope is active.
 *
 * <p>The outgoing hook runs on the app's main thread, so it only copies the
 * Intent and hands it to {@link ProbeClient}'s bounded queue.</p>
 */
public class ProbeHook implements IXposedHookLoadPackage {

    private static final String SELF_PACKAGE = "com.example.intentprobe";
    private static final String EXTRA_SKIP = "intentprobe_skip";

    /**
     * execStartActivity overloads call each other. The thread-local flag keeps
     * one launch from being reported two or three times.
     */
    private static final ThreadLocal<Boolean> IN_EXEC = new ThreadLocal<>();

    private static boolean hooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (SELF_PACKAGE.equals(lpparam.packageName)) return;
        if (hooked) return;
        try {
            hookApplicationCreate();
            hookActivity();
            hookOutgoing();
            hooked = true;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookApplicationCreate() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Application app = null;
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof Application) {
                            app = (Application) param.args[0];
                        }
                        if (app == null) return;
                        try {
                            ProbeClient.attach(app);
                            scheduleHeartbeat(app);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    private static void hookActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        capture((Activity) param.thisObject,
                                ProbeProtocol.SOURCE_ON_CREATE,
                                ((Activity) param.thisObject).getIntent());
                    }
                });
        XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = null;
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof Intent) {
                            intent = (Intent) param.args[0];
                        }
                        capture((Activity) param.thisObject,
                                ProbeProtocol.SOURCE_ON_NEW_INTENT, intent);
                    }
                });
    }

    private static void hookOutgoing() {
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(IN_EXEC.get())) {
                            param.setObjectExtra(EXTRA_SKIP, Boolean.TRUE);
                            return;
                        }
                        IN_EXEC.set(Boolean.TRUE);
                        try {
                            handleOutgoing(param);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getObjectExtra(EXTRA_SKIP))) return;
                        IN_EXEC.remove();
                    }
                });
    }

    private static void handleOutgoing(XC_MethodHook.MethodHookParam param) {
        Intent intent = null;
        Context who = null;
        Activity caller = null;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (intent == null && arg instanceof Intent) {
                    intent = (Intent) arg;
                } else if (who == null && arg instanceof Context) {
                    who = (Context) arg;
                } else if (caller == null && arg instanceof Activity) {
                    caller = (Activity) arg;
                }
            }
        }
        if (intent == null || who == null) return;

        String pkg;
        try {
            pkg = who.getPackageName();
        } catch (Throwable t) {
            return;
        }
        if (pkg == null || SELF_PACKAGE.equals(pkg)) return;
        if (!shouldRecord(intent, pkg)) return;

        String targetPkg = null;
        String targetActivity = null;
        try {
            ComponentName component = intent.getComponent();
            if (component != null) {
                targetPkg = component.getPackageName();
                if (pkg.equals(targetPkg)) {
                    targetActivity = component.getClassName();
                }
            } else {
                targetPkg = intent.getPackage();
            }
        } catch (Throwable ignored) {
        }

        String callerName = null;
        if (caller != null) {
            try {
                callerName = caller.getClass().getName();
            } catch (Throwable ignored) {
            }
        }

        ProbeClient.capture(who, pkg, ProbeProtocol.SOURCE_OUTGOING, intent,
                targetActivity, callerName);
    }

    /**
     * Keeps the outgoing stream to launches that stay inside the target app.
     * Explicit and packaged Intents are cheap to check; an implicit Intent is
     * only kept for a non-web custom scheme, where the app may handle it itself.
     */
    private static boolean shouldRecord(Intent intent, String pkg) {
        try {
            if (Intent.ACTION_MAIN.equals(intent.getAction())
                    && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                return false;
            }
            ComponentName component = intent.getComponent();
            if (component != null) {
                return pkg.equals(component.getPackageName());
            }
            String targetPkg = intent.getPackage();
            if (targetPkg != null) {
                return pkg.equals(targetPkg);
            }
            if (intent.getData() == null) return false;
            String scheme = intent.getData().getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            return !"http".equals(scheme) && !"https".equals(scheme)
                    && !"mailto".equals(scheme) && !"tel".equals(scheme)
                    && !"sms".equals(scheme) && !"smsto".equals(scheme)
                    && !"market".equals(scheme);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void capture(Activity activity, String source, Intent intent) {
        if (activity == null || intent == null) return;
        try {
            String pkg = activity.getPackageName();
            if (pkg == null || SELF_PACKAGE.equals(pkg)) return;
            String target = activity.getComponentName() != null
                    ? activity.getComponentName().getClassName()
                    : activity.getClass().getName();
            ProbeClient.capture(activity, pkg, source, intent, target);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void scheduleHeartbeat(Application app) {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable heartbeat = new Runnable() {
            @Override
            public void run() {
                try {
                    ProbeClient.hello(app, app.getPackageName());
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                handler.postDelayed(this, ProbeProtocol.HEARTBEAT_MS);
            }
        };
        handler.postDelayed(heartbeat, ProbeProtocol.HEARTBEAT_MS);
    }
}

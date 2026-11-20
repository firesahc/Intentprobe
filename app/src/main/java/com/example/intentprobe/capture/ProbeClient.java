package com.example.intentprobe.capture;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedBridge;

/**
 * Target-process side of the capture path.
 *
 * <p>Activity callbacks run on the app's main thread, so the hook only copies
 * the Intent into a small payload and offers it to a bounded queue. A single
 * low-priority daemon thread batches inserts into {@link ProbeProvider}. A
 * full queue drops new events instead of blocking the target app.</p>
 */
public final class ProbeClient {

    private static final String SELF_PACKAGE = "com.example.intentprobe";
    private static final int QUEUE_CAPACITY = 64;
    private static final int BATCH_SIZE = ProbeProtocol.MAX_BATCH_EVENTS;

    private static final ProbeClient INSTANCE = new ProbeClient();

    private final ArrayBlockingQueue<Payload> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile Context appContext;
    private volatile boolean workerStarted;
    private volatile long lastErrorLogAt;

    private ProbeClient() {}

    public static void attach(Application app) {
        if (app == null) return;
        INSTANCE.appContext = app.getApplicationContext();
        hello(app, app.getPackageName());
    }

    public static void hello(Context context, String pkg) {
        if (context == null || pkg == null || SELF_PACKAGE.equals(pkg)) return;
        try {
            Payload payload = new Payload();
            payload.hello = true;
            payload.pkg = pkg;
            payload.process = ProbeProtocol.currentProcessName(pkg);
            payload.pid = ProbeProtocol.currentPid();
            payload.timestamp = System.currentTimeMillis();
            INSTANCE.enqueue(payload, context);
        } catch (Throwable t) {
            INSTANCE.logThrottled(t);
        }
    }

    public static void capture(Context context, String pkg, String source, Intent intent,
            String target) {
        capture(context, pkg, source, intent, target, null);
    }

    public static void capture(Context context, String pkg, String source, Intent intent,
            String target, String caller) {
        if (context == null || pkg == null || intent == null) return;
        if (SELF_PACKAGE.equals(pkg)) return;
        try {
            Payload payload = new Payload();
            payload.pkg = pkg;
            payload.process = ProbeProtocol.currentProcessName(pkg);
            payload.pid = ProbeProtocol.currentPid();
            payload.source = source;
            payload.timestamp = System.currentTimeMillis();
            payload.target = target;
            payload.targetPkg = intentPackage(intent);
            payload.caller = caller;
            payload.action = intent.getAction();
            payload.data = intent.getDataString();
            payload.type = intent.getType();
            payload.categories = joinCategories(intent.getCategories());
            payload.flags = intent.getFlags();

            Bundle extras;
            try {
                extras = intent.getExtras();
            } catch (Throwable t) {
                extras = null;
            }
            payload.extrasJson = ProbeProtocol.readExtras(extras);
            INSTANCE.enqueue(payload, context);
        } catch (Throwable t) {
            INSTANCE.logThrottled(t);
        }
    }

    private static String intentPackage(Intent intent) {
        try {
            if (intent.getComponent() != null) {
                return intent.getComponent().getPackageName();
            }
            return intent.getPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    private void enqueue(Payload payload, Context context) {
        if (appContext == null && context != null) {
            Context application = context.getApplicationContext();
            appContext = application == null ? context : application;
        }
        if (!queue.offer(payload)) {
            long count = dropped.incrementAndGet();
            if (count % 100 == 1) {
                log("[Intentprobe] queue full, dropped " + count + " events");
            }
            return;
        }
        ensureWorker();
    }

    private synchronized void ensureWorker() {
        if (workerStarted) return;
        workerStarted = true;
        Thread thread = new Thread(this::runWorker, "intentprobe-hook");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void runWorker() {
        while (true) {
            Payload first;
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            List<Payload> batch = new ArrayList<>(BATCH_SIZE);
            batch.add(first);
            queue.drainTo(batch, BATCH_SIZE - 1);

            List<Payload> events = new ArrayList<>(batch.size());
            for (Payload payload : batch) {
                if (payload.hello) {
                    sendHello(payload);
                } else {
                    events.add(payload);
                }
            }
            if (!events.isEmpty()) {
                sendEvents(events);
            }
        }
    }

    private void sendHello(Payload payload) {
        Context context = appContext;
        if (context == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("pkg", payload.pkg);
            values.put("process", payload.process);
            values.put("pid", payload.pid);
            values.put("module_version", ProbeProtocol.MODULE_VERSION);
            values.put("ts", payload.timestamp);
            context.getContentResolver().insert(Uri.parse(ProbeProtocol.URI_HELLO), values);
        } catch (Throwable t) {
            logThrottled(t);
        }
    }

    private void sendEvents(List<Payload> events) {
        Context context = appContext;
        if (context == null) return;

        Map<String, List<Payload>> groups = new LinkedHashMap<>();
        for (Payload payload : events) {
            String key = payload.pkg + '|' + payload.process + '|' + payload.pid;
            List<Payload> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(payload);
        }

        for (List<Payload> group : groups.values()) {
            Payload first = group.get(0);
            JSONArray array = new JSONArray();
            for (Payload payload : group) {
                JSONObject object = toJson(payload);
                if (object != null) array.put(object);
            }
            if (array.length() == 0) continue;
            try {
                ContentValues values = new ContentValues();
                values.put("pkg", first.pkg);
                values.put("process", first.process);
                values.put("pid", first.pid);
                values.put("batch", array.toString());
                context.getContentResolver().insert(Uri.parse(ProbeProtocol.URI_EVENTS), values);
            } catch (Throwable t) {
                logThrottled(t);
            }
        }
    }

    private static JSONObject toJson(Payload payload) {
        try {
            JSONObject object = new JSONObject();
            object.put("src", payload.source);
            object.put("ts", payload.timestamp);
            if (payload.target != null) object.put("target", payload.target);
            if (payload.targetPkg != null) object.put("tpkg", payload.targetPkg);
            if (payload.caller != null) object.put("caller", payload.caller);
            if (payload.action != null) object.put("action", payload.action);
            if (payload.data != null) object.put("data", payload.data);
            if (payload.type != null) object.put("type", payload.type);
            if (payload.categories != null) object.put("cat", payload.categories);
            object.put("flags", payload.flags);
            if (payload.extrasJson != null) {
                object.put("extras", new JSONArray(payload.extrasJson));
            }
            return object;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String joinCategories(Set<String> categories) {
        if (categories == null || categories.isEmpty()) return null;
        StringBuilder builder = new StringBuilder();
        for (String category : categories) {
            if (builder.length() > 0) builder.append(',');
            builder.append(category);
        }
        return builder.toString();
    }

    private void logThrottled(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt < 60_000L) return;
        lastErrorLogAt = now;
        XposedBridge.log(t);
    }

    private static void log(String message) {
        try {
            XposedBridge.log(message);
        } catch (Throwable ignored) {
        }
    }

    private static final class Payload {
        boolean hello;
        String pkg;
        String process;
        int pid;
        String source;
        long timestamp;
        String target;
        String targetPkg;
        String caller;
        String action;
        String data;
        String type;
        String categories;
        int flags;
        String extrasJson;
    }
}

package io.github.jahn2007.fourma;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/**
 * Read-only first-stage probe for the WeChat Mini Program runtime.
 *
 * The probe deliberately avoids network hooks and arbitrary text logging. Its output is intended
 * to identify the 7MA AppID, page route, host Activity and useful view/runtime class names so that
 * later versions can target stable boundaries instead of guessing from the WeChat APK.
 */
public class DiagnosticModule extends XposedModule {
    private static final String TAG = "4MA";
    private static final int MAX_EVENTS = 320;
    private static final int MAX_VIEWS_PER_SCAN = 500;
    private static final Pattern MASKED_VEHICLE = Pattern.compile(
            ".*[0-9]{2,}[\\s-]*[*＊•·]+[\\s-]*[0-9]{1,}.*");
    private static final Pattern APP_ID = Pattern.compile("^wx[0-9A-Za-z_-]{8,40}$");
    private static final Pattern USER_NAME = Pattern.compile(
            "^gh_[0-9A-Za-z_-]{6,40}(?:@app)?$");
    private static final Pattern ROUTE = Pattern.compile(
            "^(?:pages?|subpackages?|package|components?)/[0-9A-Za-z_./%-]{1,200}$");

    private final Object reportLock = new Object();
    private final Set<String> seenEvents = Collections.newSetFromMap(new java.util.HashMap<>());
    private final List<Bundle> pendingReports = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String processName = "unknown";
    private Context targetContext;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = clean(param.getProcessName(), 96);
        prefs = getRemotePreferences(Prefs.GROUP);
        reportAlways("environment", "pid=" + Process.myPid()
                + " api=" + getApiVersion()
                + " framework=" + clean(getFrameworkName(), 80));
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!"com.tencent.mm".equals(param.getPackageName())) {
            detach();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.tencent.mm".equals(param.getPackageName())) return;

        if (!processName.startsWith("com.tencent.mm:appbrand")) {
            reportAlways("scope", "main process observed");
            installMainProcessSignalHook();
            return;
        }

        reportAlways("scope", "appbrand process ready classLoader="
                + className(param.getClassLoader()));
        installHooks();
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // Remove every delayed callback that captures this module generation. Hook invocations
        // already in flight are allowed to finish against their existing chain snapshot.
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (reportLock) {
            pendingReports.clear();
        }
        targetContext = null;
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Unable to remove an old hook during reload", error);
            }
        }

        processName = clean(param.getProcessName(), 96);
        prefs = getRemotePreferences(Prefs.GROUP);
        targetContext = resolveCurrentApplication();
        reportAlways("hot_reload", "new generation loaded; oldHooks="
                + param.getOldHookHandles().size());
        flushPendingReports();

        if ("com.tencent.mm".equals(processName)) {
            installMainProcessSignalHook();
        } else if (processName.startsWith("com.tencent.mm:appbrand")) {
            installHooks();
        } else {
            detach();
        }
    }

    private void installMainProcessSignalHook() {
        tryHook("Instrumentation.callActivityOnResume(main signal)", () -> {
            Method method = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnResume", Activity.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                Activity activity = (Activity) chain.getArg(0);
                if (activity != null) {
                    targetContext = activity.getApplicationContext();
                    flushPendingReports();
                    reportAlways("main_activity", activity.getClass().getName());
                }
                return result;
            });
        });
    }

    private void installHooks() {
        tryHook("Instrumentation.callActivityOnResume", () -> {
            Method method = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnResume", Activity.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                Activity activity = (Activity) chain.getArg(0);
                if (activity != null && captureEnabled()) inspectActivitySoon(activity);
                return result;
            });
        });

        tryHook("Dialog.show", () -> {
            Method method = Dialog.class.getDeclaredMethod("show");
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (captureEnabled()) {
                    Dialog dialog = (Dialog) chain.getThisObject();
                    inspectDialogSoon(dialog);
                }
                return result;
            });
        });

        tryHook("TextView.setText", () -> {
            Method method = TextView.class.getDeclaredMethod(
                    "setText", CharSequence.class, TextView.BufferType.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (captureEnabled()) detectBusinessMarker(chain.getArg(0));
                return chain.proceed();
            });
        });

        tryHook("Canvas.drawText(String)", () -> {
            Method method = Canvas.class.getDeclaredMethod(
                    "drawText", String.class, float.class, float.class, Paint.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (captureEnabled()) detectBusinessMarker(chain.getArg(0));
                return chain.proceed();
            });
        });

        tryHook("Canvas.drawText(CharSequence)", () -> {
            Method method = Canvas.class.getDeclaredMethod(
                    "drawText", CharSequence.class, int.class, int.class,
                    float.class, float.class, Paint.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (captureEnabled()) {
                    Object value = chain.getArg(0);
                    if (value instanceof CharSequence) {
                        int start = (Integer) chain.getArg(1);
                        int end = (Integer) chain.getArg(2);
                        CharSequence sequence = (CharSequence) value;
                        if (start >= 0 && end >= start && end <= sequence.length()) {
                            detectBusinessMarker(sequence.subSequence(start, end));
                        }
                    }
                }
                return chain.proceed();
            });
        });
    }

    private interface HookInstall {
        void run() throws Throwable;
    }

    private void tryHook(String name, HookInstall install) {
        try {
            install.run();
            reportAlways("hook", name + " installed");
        } catch (Throwable error) {
            reportAlways("hook_error", name + " " + error.getClass().getName());
            log(Log.ERROR, TAG, "Unable to install " + name, error);
        }
    }

    private void inspectActivitySoon(Activity activity) {
        targetContext = activity.getApplicationContext();
        flushPendingReports();
        report("activity", activity.getClass().getName());
        inspectIntent(activity.getIntent());
        inspectWhitelistedFields(activity, "activity", 2);
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root != null) {
            scanViews(root);
            mainHandler.postDelayed(() -> {
                if (captureEnabled()) scanViews(root);
            }, 1_200L);
        }
    }

    private void inspectDialogSoon(Dialog dialog) {
        if (dialog == null) return;
        report("dialog", dialog.getClass().getName());
        mainHandler.postDelayed(() -> {
            try {
                Window window = dialog.getWindow();
                if (captureEnabled() && window != null) scanViews(window.getDecorView());
            } catch (Throwable error) {
                report("inspect_error", "dialog " + error.getClass().getSimpleName());
            }
        }, 250L);
    }

    private Context resolveCurrentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getDeclaredMethod("currentApplication")
                    .invoke(null);
            if (application instanceof Context) {
                return ((Context) application).getApplicationContext();
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Unable to resolve current Application during hot reload", error);
        }
        return null;
    }

    private void inspectIntent(Intent intent) {
        if (intent == null) return;
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (Throwable error) {
            report("inspect_error", "intent " + error.getClass().getSimpleName());
            return;
        }
        if (extras == null) return;
        for (String key : extras.keySet()) {
            if (key == null || containsSensitiveName(key.toLowerCase(Locale.ROOT))) continue;
            Object value;
            try {
                value = extras.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            report("intent_key", clean(key, 140) + " type=" + className(value));
            reportIdentifierCandidate("intent", key, value);
            if (value != null && isRelevantRuntimeClass(value.getClass().getName())) {
                inspectWhitelistedFields(value, "intent." + clean(key, 80), 3);
            }
        }
    }

    private void inspectWhitelistedFields(Object start, String source, int maxDepth) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        inspectWhitelistedFields(start, source, 0, maxDepth, visited);
    }

    private void inspectWhitelistedFields(Object object, String source, int depth, int maxDepth,
                                          Set<Object> visited) {
        if (object == null || depth > maxDepth || visited.size() >= 160 || !visited.add(object)) {
            return;
        }

        Class<?> type = object.getClass();
        String owner = type.getName();
        if (depth > 0 && !isRelevantRuntimeClass(owner)) return;
        if (isRelevantRuntimeClass(owner)) report("runtime_class", owner);

        int inspected = 0;
        for (Class<?> cursor = type; cursor != null && cursor != Object.class && inspected < 80;
             cursor = cursor.getSuperclass()) {
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (++inspected > 80 || Modifier.isStatic(field.getModifiers())) break;
                String name = field.getName();
                String normalized = name.toLowerCase(Locale.ROOT);
                if (containsSensitiveName(normalized)) continue;
                boolean valueField = isWhitelistedField(normalized);
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (valueField && (value instanceof Number || value instanceof Boolean)) {
                        reportSafeValue("runtime_field", source + "." + name, value);
                    }
                    reportIdentifierCandidate("runtime_field", source + "." + name, value);
                    if (depth < maxDepth && value != null
                            && isRelevantRuntimeClass(value.getClass().getName())) {
                        inspectWhitelistedFields(value, source + "." + name,
                                depth + 1, maxDepth, visited);
                    }
                } catch (Throwable ignored) {
                    // A failed reflective read is expected on some WeChat builds.
                }
            }
        }
    }

    private boolean isWhitelistedField(String name) {
        if (containsSensitiveName(name)) return false;
        return name.equals("appid") || name.equals("app_id") || name.endsWith("appid")
                || name.equals("username") || name.endsWith("username")
                || name.equals("route") || name.endsWith("route")
                || name.equals("path") || name.endsWith("pagepath")
                || name.equals("currentpage") || name.equals("current_page");
    }

    private boolean containsSensitiveName(String name) {
        return name.contains("token") || name.contains("cookie") || name.contains("session")
                || name.contains("secret") || name.contains("password")
                || name.contains("authorization") || name.contains("signature")
                || name.contains("phone") || name.contains("mobile");
    }

    private boolean isRelevantRuntimeClass(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("appbrand") || lower.contains("miniprogram")
                || lower.contains("weapp") || lower.contains("weixin");
    }

    private void reportSafeValue(String kind, String key, Object value) {
        if (value == null) return;
        if (value instanceof CharSequence) {
            String text = clean(value.toString(), 220);
            if (!text.isEmpty()) report(kind, clean(key, 100) + "=" + text);
        } else if (value instanceof Number || value instanceof Boolean) {
            report(kind, clean(key, 100) + "=" + value);
        }
    }

    private void reportIdentifierCandidate(String kind, String key, Object value) {
        if (!(value instanceof CharSequence)) return;
        String text = value.toString().trim();
        if (text.isEmpty() || text.length() > 240) return;
        if (APP_ID.matcher(text).matches()) {
            report(kind, clean(key, 120) + " appId=" + text);
        } else if (USER_NAME.matcher(text).matches()) {
            report(kind, clean(key, 120) + " userName=" + text);
        } else {
            int query = text.indexOf('?');
            String path = query >= 0 ? text.substring(0, query) : text;
            if (ROUTE.matcher(path).matches()) {
                report(kind, clean(key, 120) + " route=" + clean(path, 220));
            }
        }
    }

    private void scanViews(View root) {
        if (root == null) return;
        ArrayDeque<View> queue = new ArrayDeque<>();
        Set<View> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count++ < MAX_VIEWS_PER_SCAN) {
            View view = queue.removeFirst();
            if (!visited.add(view)) continue;
            String name = view.getClass().getName();
            if (verboseEnabled() && isInterestingViewClass(name)) {
                report("view_class", name);
            }
            if (view instanceof TextView) {
                detectBusinessMarker(((TextView) view).getText());
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int childCount = Math.min(group.getChildCount(), MAX_VIEWS_PER_SCAN - count);
                for (int i = 0; i < childCount; i++) {
                    View child = group.getChildAt(i);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        report("view_scan", "root=" + root.getClass().getName() + " visited=" + visited.size());
    }

    private boolean isInterestingViewClass(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("appbrand") || lower.contains("tbs") || lower.contains("xweb")
                || lower.contains("map") || lower.startsWith("com.tencent.mm");
    }

    private void detectBusinessMarker(Object value) {
        if (!(value instanceof CharSequence)) return;
        String text = value.toString().trim();
        if (text.isEmpty() || text.length() > 180) return;

        marker(text, "车辆所属运营区", "cross_region_prompt");
        marker(text, "继续用车", "continue_riding");
        marker(text, "开始用车", "start_riding");
        marker(text, "我要还车", "return_vehicle");
        marker(text, "确认还车", "confirm_return");
        marker(text, "临时上锁", "temporary_lock");
        marker(text, "临时锁车", "temporary_lock");
        marker(text, "再次开锁", "unlock_again");
        marker(text, "20分钟", "twenty_minute_rule");
        marker(text, "输入车辆编号", "vehicle_number_input");
        marker(text, "输入车编号", "vehicle_number_input");
        if ("还车".equals(text)) report("ui_marker", "return_vehicle");
        if ("锁车".equals(text)) report("ui_marker", "temporary_lock");
        if (MASKED_VEHICLE.matcher(text).matches()) {
            report("ui_marker", "masked_vehicle_number shape=" + maskShape(text));
        }
    }

    private void marker(String text, String needle, String marker) {
        if (text.contains(needle)) report("ui_marker", marker);
    }

    private String maskShape(String value) {
        StringBuilder shape = new StringBuilder();
        for (int i = 0; i < value.length() && shape.length() < 80; i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) shape.append('D');
            else if (Character.isLetter(c)) shape.append('A');
            else if (c == '*' || c == '＊' || c == '•' || c == '·') shape.append('*');
            else if (Character.isWhitespace(c)) shape.append('_');
            else shape.append('-');
        }
        return shape.toString();
    }

    private boolean captureEnabled() {
        return prefs != null && prefs.getBoolean(Prefs.KEY_CAPTURE_ENABLED, true);
    }

    private boolean verboseEnabled() {
        return prefs != null && prefs.getBoolean(Prefs.KEY_DEBUG_VERBOSE, false);
    }

    private void report(String kind, String detail) {
        if (captureEnabled()) appendReport(kind, detail);
    }

    private void reportAlways(String kind, String detail) {
        appendReport(kind, detail);
    }

    private void appendReport(String kind, String detail) {
        if (prefs == null) return;
        String safeKind = clean(kind, 48);
        String safeDetail = clean(detail, 500);
        String eventKey = safeKind + '|' + safeDetail;
        synchronized (reportLock) {
            if (seenEvents.size() >= MAX_EVENTS || !seenEvents.add(eventKey)) return;
            String line = System.currentTimeMillis() + "|" + processName + "|"
                    + safeKind + "|" + safeDetail + '\n';
            Bundle payload = new Bundle();
            payload.putString(ReportProvider.EXTRA_REPORT_KEY, Prefs.reportKey(processName));
            payload.putString(ReportProvider.EXTRA_LINE, line);
            if (!sendReport(payload) && pendingReports.size() < 40) {
                pendingReports.add(payload);
            }
        }
    }

    private boolean sendReport(Bundle payload) {
        Context context = targetContext;
        if (context == null) return false;
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + ReportProvider.AUTHORITY),
                    ReportProvider.METHOD_APPEND, null, payload);
            return result != null && result.getBoolean("accepted", false);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Report IPC failed", error);
            return false;
        }
    }

    private void flushPendingReports() {
        synchronized (reportLock) {
            if (targetContext == null || pendingReports.isEmpty()) return;
            List<Bundle> copy = new ArrayList<>(pendingReports);
            pendingReports.clear();
            for (Bundle payload : copy) {
                if (!sendReport(payload) && pendingReports.size() < 40) {
                    pendingReports.add(payload);
                }
            }
        }
    }

    private String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "null";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}

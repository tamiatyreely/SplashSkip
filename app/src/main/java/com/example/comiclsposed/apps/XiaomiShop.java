package com.example.comiclsposed.apps;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Xiaomi Shop 5.54.0.20260811.r1 adapter.
 * 去除开屏广告：拦截 SplashPresenter.displaySplashView，
 * 并清理 splash 相关 View；不再全局 Hook ViewGroup.addView，
 * 避免主线程过载导致启动卡死。
 */
public final class XiaomiShop {

    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
    // 修正：实际 launcher Activity
    private static final String MAIN_ACTIVITY = "com.xiaomi.shop.activity.MainTabActivity";
    private static final String AD_DIALOG = "com.xiaomi.shop.plugin.homepage.utils.AdDialog";
    private static final int MAX_CHARS = 45000;
    private static final int MAX_NODES = 300;

    private static final StringBuilder LOG = new StringBuilder();
    private static final Set<Activity> SEEN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));
    private static volatile boolean installed;
    private static volatile boolean firstToastShown;
    private static volatile boolean splashHookAttempted;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private XiaomiShop() {}

    public static synchronized void install(XposedModule module) {
        if (installed) return;
        installed = true;
        note("================================================");
        note("SplashSkip Xiaomi Shop adapter loaded");
        note("package=" + PACKAGE + ", targetVersion=5.54.0.20260811.r1");
        note("confirmed popup class=" + AD_DIALOG);
        hookActivityCreate(module);
        hookDialogShow(module);
        hookActivityResume(module);
        note("Hooks installed; splash manager hook attaches before target Activity onCreate.");
    }

    // ------------------------------------------------------------------
    // Activity.onCreate：在原始 onCreate 之前挂 SplashPresenter Hook
    // ------------------------------------------------------------------
    private static void hookActivityCreate(XposedModule module) {
        try {
            Method create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(create).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity && isTarget((Activity) obj)) {
                    Activity activity = (Activity) obj;
                    // 必须在 proceed 之前挂，否则 displaySplashView 可能已被调用
                    hookSplashManager(module, activity.getClassLoader());
                }
                Object result = chain.proceed();
                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;
                    if (isTarget(activity)) {
                        String name = activity.getClass().getName();
                        note("Activity.onCreate: " + name);
                        String lower = name.toLowerCase(Locale.ROOT);
                        // 只跳过明确的独立开屏 Activity，不碰 MainTabActivity 和普通页面
                        boolean splashLike = lower.contains("splash")
                                || lower.contains("launchad")
                                || lower.contains("adlaunch");
                        if (splashLike && !MAIN_ACTIVITY.equals(name)
                                && !activity.isFinishing() && !activity.isDestroyed()) {
                            note("BLOCK splash-like Activity: " + name);
                            try {
                                android.content.Intent intent = new android.content.Intent();
                                intent.setClassName(PACKAGE, MAIN_ACTIVITY);
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                activity.startActivity(intent);
                                activity.finish();
                                activity.overridePendingTransition(0, 0);
                                note("Started " + MAIN_ACTIVITY + " to skip splash-like Activity.");
                                copyClipboard(activity);
                            } catch (Throwable t) {
                                note("Splash Activity skip failed: " + t.getClass().getSimpleName());
                            }
                        }
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onCreate (splash-like Activity detection)");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Dialog.show：仅精确拦截 AdDialog
    // ------------------------------------------------------------------
    private static void hookDialogShow(XposedModule module) {
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            module.hook(show).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Dialog) {
                    Dialog dialog = (Dialog) obj;
                    if (isTarget(dialog.getContext())
                            && AD_DIALOG.equals(dialog.getClass().getName())) {
                        note("BLOCK Dialog.show: " + AD_DIALOG);
                        note("弹窗广告已在显示前拦截。");
                        try {
                            if (dialog.isShowing()) dialog.dismiss();
                        } catch (Throwable ignored) {}
                        copyClipboard(dialog.getContext());
                        return null; // 不执行原 show
                    }
                }
                return chain.proceed();
            });
            note("Hook OK: Dialog.show (exact AdDialog match only)");
        } catch (Throwable t) {
            note("Hook FAIL Dialog.show: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Activity.onResume：轻量兜底扫描 + 复制日志
    // ------------------------------------------------------------------
    private static void hookActivityResume(XposedModule module) {
        try {
            Method resume = Activity.class.getDeclaredMethod("onResume");
            module.hook(resume).intercept(chain -> {
                Object result = chain.proceed();
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;
                    if (isTarget(activity)) {
                        String cls = activity.getClass().getName();
                        note("Activity.onResume: " + cls);
                        if (SEEN.add(activity)) {
                            note("first resume for activity; intent=" + safeIntent(activity));
                        }
                        // 只做轻量 splash 扫描，不再扫描 cover 容器，避免误伤主界面
                        scheduleSplashScan(activity, 0);
                        scheduleSplashScan(activity, 300);
                        scheduleCopy(activity, 600);
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onResume");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onResume: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Hook SplashPresenter / SplashViewManager
    // ------------------------------------------------------------------
    private static synchronized void hookSplashManager(XposedModule module, ClassLoader loader) {
        if (splashHookAttempted) return;
        splashHookAttempted = true;

        String[] targets = {
                "com.xiaomi.shop.splash.SplashViewManager",
                "com.xiaomi.shop.presenter.SplashPresenter"
        };
        int hooked = 0;

        for (String className : targets) {
            Class<?> cls;
            try {
                cls = Class.forName(className, false, loader);
            } catch (Throwable t) {
                note("Splash class unavailable " + className + ": " + t.getClass().getSimpleName());
                continue;
            }
            for (Method method : cls.getDeclaredMethods()) {
                String methodName = method.getName();
                String lower = methodName.toLowerCase(Locale.ROOT);
                boolean display = "displaySplashView".equals(methodName);
                boolean cleanup = lower.contains("dismiss")
                        || lower.contains("hide")
                        || lower.contains("remove");
                if (!display && !cleanup) continue;

                try {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        if (display) {
                            note("BLOCK splash display method: " + method.toGenericString());
                            hideSplashFields(receiver);
                            return defaultValue(method.getReturnType());
                        }
                        // dismiss/hide/remove：正常执行，然后顺手清理 splash View
                        Object r = chain.proceed();
                        hideSplashFields(receiver);
                        return r;
                    });
                    hooked++;
                    note("Hook OK splash manager method: " + method.toGenericString());
                } catch (Throwable t) {
                    note("Hook FAIL splash manager method " + methodName + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
        note("Splash display methods hooked=" + hooked);
    }

    /** 反射把 SplashPresenter 里持有的 View 字段设为 GONE，防止 splash 残影。 */
    private static void hideSplashFields(Object receiver) {
        if (receiver == null) return;
        try {
            for (Field field : receiver.getClass().getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);
                    if (value instanceof View) {
                        View view = (View) value;
                        view.post(() -> {
                            try {
                                view.setVisibility(View.GONE);
                                note("GONE splash field view: " + field.getName()
                                        + " class=" + view.getClass().getName());
                            } catch (Throwable ignored) {}
                        });
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        if (type == Character.TYPE) return '\0';
        return null;
    }

    // ------------------------------------------------------------------
    // 轻量 splash View 扫描（只按 splash 关键字识别，避免误伤主界面）
    // ------------------------------------------------------------------
    private static void scheduleSplashScan(Activity activity, long delayMs) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(() -> {
                try {
                    scanAndHideSplash(decor, new int[]{0});
                } catch (Throwable t) {
                    note("splash scan error: " + t.getClass().getSimpleName());
                }
            }, delayMs);
        } catch (Throwable ignored) {}
    }

    private static void scanAndHideSplash(View view, int[] count) {
        if (view == null || count[0]++ > MAX_NODES) return;
        if (isSplashView(view)) {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                note("BLOCK splash view: " + describe(view));
                copyClipboard(view.getContext());
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && count[0] < MAX_NODES; i++) {
                scanAndHideSplash(group.getChildAt(i), count);
            }
        }
    }

    private static boolean isSplashView(View view) {
        String cls = view.getClass().getName();
        String id = resourceName(view);
        if ("com.xiaomi.shop.view.SplashActionView".equals(cls)) return true;
        String lowerCls = cls.toLowerCase(Locale.ROOT);
        String lowerId = id.toLowerCase(Locale.ROOT);
        // 只认类名/资源名里明确带 splash 的 View
        if (lowerCls.contains("splash")) return true;
        if (lowerId.contains("splash")) return true;
        return false;
    }

    private static void scheduleCopy(Activity activity, long delayMs) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.postDelayed(() -> copyClipboard(activity), delayMs);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------
    private static boolean isTarget(Context context) {
        try { return context != null && PACKAGE.equals(context.getPackageName()); }
        catch (Throwable ignored) { return false; }
    }

    private static String safeIntent(Activity a) {
        try {
            if (a.getIntent() == null) return "null";
            return "action=" + a.getIntent().getAction()
                    + ", data=" + (a.getIntent().getData() == null
                    ? "null" : scrubUrl(a.getIntent().getData().toString()));
        } catch (Throwable t) { return "unavailable"; }
    }

    private static String resourceName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) { return ""; }
    }

    private static String describe(View v) {
        return v.getClass().getName() + " id=" + resourceName(v)
                + " visibility=" + v.getVisibility();
    }

    private static synchronized void note(String message) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new java.util.Date()) + " " + redact(message);
        LOG.append(line).append('\n');
        if (LOG.length() > MAX_CHARS) LOG.delete(0, LOG.length() - MAX_CHARS);
        Log.i(TAG, line);
    }

    private static String redact(String s) {
        if (s == null) return "null";
        return s.replaceAll(
                "(?i)(token|cookie|session|authorization|password|phone|mobile)\\s*[:=]\\s*[^,; ]+",
                "$1=<hidden>");
    }

    private static String scrubUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?<query-hidden>" : url;
    }

    private static void copyClipboard(Context context) {
        if (!isTarget(context)) return;
        try {
            ClipboardManager manager = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            String text;
            synchronized (XiaomiShop.class) { text = LOG.toString(); }
            manager.setPrimaryClip(ClipData.newPlainText("SplashSkip Xiaomi Shop log", text));
            if (!firstToastShown && context instanceof Activity) {
                firstToastShown = true;
                Toast.makeText(context, "SplashSkip：小米商城日志已复制到剪贴板",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "clipboard copy failed: " + t.getClass().getSimpleName());
        }
    }
}

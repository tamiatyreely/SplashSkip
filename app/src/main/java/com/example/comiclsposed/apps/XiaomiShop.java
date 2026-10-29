package com.example.comiclsposed.apps;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
 * 去开屏：
 *   1) MainTabActivity 是 splash 壳 → onCreate 后主动跳 MainActivity
 *   2) displaySplashView proceed + 30/200/800ms 反复 removeSplashTree 兜底
 *   3) 扫描条件放宽到 id/class 含 "splash" 即命中，整棵子树 remove
 */
public final class XiaomiShop {

    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
    // 真正的首页（日志里 4 秒后跳到这里）
    private static final String MAIN_ACTIVITY = "com.xiaomi.shop2.activity.MainActivity";
    // splash 壳
    private static final String SPLASH_ACTIVITY = "com.xiaomi.shop.activity.MainTabActivity";
    private static final String AD_DIALOG = "com.xiaomi.shop.plugin.homepage.utils.AdDialog";
    private static final int MAX_CHARS = 45000;
    private static final int MAX_NODES = 400;

    private static final StringBuilder LOG = new StringBuilder();
    private static final Set<Activity> SEEN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));
    private static volatile boolean installed;
    private static volatile boolean firstToastShown;
    private static volatile boolean splashHookAttempted;
    private static volatile boolean redirected;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private XiaomiShop() {}

    public static synchronized void install(XposedModule module) {
        if (installed) return;
        installed = true;
        note("================================================");
        note("SplashSkip Xiaomi Shop adapter loaded");
        note("package=" + PACKAGE + ", targetVersion=5.54.0.20260811.r1");
        hookActivityCreate(module);
        hookDialogShow(module);
        hookActivityResume(module);
        note("Hooks installed (redirect + removeSplashTree)");
    }

    // ------------------------------------------------------------------
    // Activity.onCreate
    // ------------------------------------------------------------------
    private static void hookActivityCreate(XposedModule module) {
        try {
            Method create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(create).intercept(chain -> {
                Object obj = chain.getThisObject();
                Activity activity = obj instanceof Activity ? (Activity) obj : null;
                if (activity != null && isTarget(activity)) {
                    hookSplashManager(module, activity.getClassLoader());
                }
                Object result = chain.proceed();
                if (activity != null && isTarget(activity)) {
                    String name = activity.getClass().getName();
                    note("Activity.onCreate: " + name);
                    // splash 壳：onCreate 完立刻跳真正的首页
                    if (SPLASH_ACTIVITY.equals(name) && !redirected) {
                        redirected = true;
                        MAIN.postDelayed(() -> redirectToMain(activity), 150);
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onCreate");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void redirectToMain(Activity splashActivity) {
        try {
            if (splashActivity.isFinishing() || splashActivity.isDestroyed()) return;
            Intent intent = new Intent();
            intent.setClassName(PACKAGE, MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            splashActivity.startActivity(intent);
            note("Redirected to " + MAIN_ACTIVITY);
            splashActivity.finish();
            splashActivity.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            note("redirect failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            redirected = false; // 允许重试
        }
    }

    // ------------------------------------------------------------------
    // Dialog.show：仅拦截 AdDialog
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
                        try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) {}
                        copyClipboard(dialog.getContext());
                        return null;
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
    // Activity.onResume：兜底 removeSplashTree + 复制日志
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
                        note("Activity.onResume: " + activity.getClass().getName());
                        if (SEEN.add(activity)) {
                            note("first resume for activity; intent=" + safeIntent(activity));
                        }
                        scheduleRemoveSplash(activity);
                        scheduleCopy(activity, 1500);
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onResume");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onResume: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void scheduleRemoveSplash(Activity activity) {
        MAIN.postDelayed(() -> removeSplashTree(activity), 30);
        MAIN.postDelayed(() -> removeSplashTree(activity), 200);
        MAIN.postDelayed(() -> removeSplashTree(activity), 800);
    }

    // ------------------------------------------------------------------
    // Hook SplashPresenter.displaySplashView：proceed 后连续 remove
    // ------------------------------------------------------------------
    private static synchronized void hookSplashManager(XposedModule module, ClassLoader loader) {
        if (splashHookAttempted) return;
        splashHookAttempted = true;

        String[] targets = {
                "com.xiaomi.shop.presenter.SplashPresenter",
                "com.xiaomi.shop.splash.SplashViewManager"
        };
        int hooked = 0;

        for (String className : targets) {
            Class<?> cls;
            try {
                cls = Class.forName(className, false, loader);
            } catch (Throwable t) {
                note("Splash class unavailable " + className);
                continue;
            }
            for (Method method : cls.getDeclaredMethods()) {
                if (!"displaySplashView".equals(method.getName())) continue;
                try {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        note("Splash displayed; scheduling removal: " + method.toGenericString());
                        MAIN.postDelayed(XiaomiShop::removeSplashOnCurrentActivity, 30);
                        MAIN.postDelayed(XiaomiShop::removeSplashOnCurrentActivity, 200);
                        MAIN.postDelayed(XiaomiShop::removeSplashOnCurrentActivity, 800);
                        return result;
                    });
                    hooked++;
                    note("Hook OK splash display: " + method.toGenericString());
                } catch (Throwable t) {
                    note("Hook FAIL splash method: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
        note("Splash display methods hooked=" + hooked);
    }

    private static void removeSplashOnCurrentActivity() {
        try {
            Activity a = getCurrentActivity();
            if (a == null) return;
            removeSplashTree(a);
        } catch (Throwable t) {
            note("removeSplash error: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 在 activity 里找到含 splash 的子树，从 contentView / decorView 的
     * 直接子层级整棵 remove。
     * 只要 id / 类名里含 "splash" 就算命中，避免之前
     * bg_splash_logo_img / bg_splash_logo_lin 这种 id 匹配不到的问题。
     */
    private static void removeSplashTree(Activity activity) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup decorGroup = (ViewGroup) decor;

            View content = decorGroup.findViewById(android.R.id.content);

            // 1) contentView 内部（splash 通常挂在 content 里）
            if (content instanceof ViewGroup) {
                ViewGroup contentGroup = (ViewGroup) content;
                for (int i = contentGroup.getChildCount() - 1; i >= 0; i--) {
                    View child = contentGroup.getChildAt(i);
                    if (containsSplash(child, new int[]{0})) {
                        contentGroup.removeViewAt(i);
                        note("Removed splash in contentView: " + describe(child));
                    }
                }
            }

            // 2) decorView 非 content 的直接子（overlay 形式的 splash）
            for (int i = decorGroup.getChildCount() - 1; i >= 0; i--) {
                View child = decorGroup.getChildAt(i);
                if (child == content) continue;
                if (containsSplash(child, new int[]{0})) {
                    decorGroup.removeViewAt(i);
                    note("Removed splash overlay in decor: " + describe(child));
                }
            }
        } catch (Throwable t) {
            note("removeSplashTree error: " + t.getClass().getSimpleName());
        }
    }

    /** id / 类名含 "splash" 即视为命中（不区分大小写）。 */
    private static boolean containsSplash(View v, int[] count) {
        if (v == null || count[0]++ > MAX_NODES) return false;
        String cls = v.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceName(v).toLowerCase(Locale.ROOT);
        if (cls.contains("splash") || id.contains("splash")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && count[0] < MAX_NODES; i++) {
                if (containsSplash(g.getChildAt(i), count)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 反射获取当前前台 Activity
    // ------------------------------------------------------------------
    private static Activity getCurrentActivity() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAT = atClass.getDeclaredMethod("currentActivityThread");
            Object thread = currentAT.invoke(null);
            Field activitiesField = atClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activities = activitiesField.get(thread);
            if (activities instanceof java.util.Map) {
                for (Object record : ((java.util.Map<?, ?>) activities).values()) {
                    Field actField = record.getClass().getDeclaredField("activity");
                    actField.setAccessible(true);
                    Object act = actField.get(record);
                    if (act instanceof Activity && !((Activity) act).isFinishing()) {
                        return (Activity) act;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------
    private static void scheduleCopy(Activity activity, long delayMs) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.postDelayed(() -> copyClipboard(activity), delayMs);
        } catch (Throwable ignored) {}
    }

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
        return v.getClass().getName() + " id=" + resourceName(v);
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

package com.example.comiclsposed.apps;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Xiaomi Shop 5.54.0.20260811.r1 adapter.
 *
 * 功能：
 *  1) 开屏：MainTabActivity 是 splash 壳 → onCreate 后主动跳 MainActivity；
 *     displaySplashView proceed + 30/200/800ms 反复 removeSplashTree 兜底。
 *  2) 弹窗：精确拦截 AdDialog.show。
 *  3) 悬浮广告：onResume 后 1.5/3/6s 扫描 decor/content 直接子层，
 *     类名或 id 命中明确悬浮广告关键词就 remove；疑似广告只打日志。
 *  4) 日志仅输出到 Logcat（TAG: SplashSkip-Xiaomi），不使用剪贴板、不弹 Toast。
 */
public final class XiaomiShop {

    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
    private static final String MAIN_ACTIVITY = "com.xiaomi.shop2.activity.MainActivity";
    private static final String SPLASH_ACTIVITY = "com.xiaomi.shop.activity.MainTabActivity";
    private static final String AD_DIALOG = "com.xiaomi.shop.plugin.homepage.utils.AdDialog";
    private static final int MAX_CHARS = 45000;
    private static final int MAX_NODES = 400;

    // 明确悬浮广告关键词（类名或 id 小写后包含其中任一 → 直接 remove）
    private static final String[] FLOATING_AD_KEYWORDS = {
            "floatad", "floatingad", "suspensionad", "popupad",
            "adfloat", "ad_popup", "ad_float", "adfloatlayer",
            "adfloating", "floating_ad", "float_ad", "suspension_ad"
    };

    // 疑似广告特征（类名或 id 含其中之一 → 只打 Logcat 日志，不 remove）
    private static final String[] AD_HINT_KEYWORDS = {
            "advert", "adlayer", "adbanner", "adpopup",
            "adview", "ad_view", "adcontainer", "ad_container"
    };

    private static final StringBuilder LOG = new StringBuilder();
    private static final Set<Activity> SEEN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));
    private static volatile boolean installed;
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
        note("Hooks installed (redirect + removeSplashTree + floating-ad-removal)");
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
            redirected = false;
        }
    }

    // ------------------------------------------------------------------
    // Dialog.show
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
    // Activity.onResume
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
                        scheduleFloatingAdScan(activity);
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
    // Splash 清理
    // ------------------------------------------------------------------
    private static void scheduleRemoveSplash(Activity activity) {
        MAIN.postDelayed(() -> removeSplashTree(activity), 30);
        MAIN.postDelayed(() -> removeSplashTree(activity), 200);
        MAIN.postDelayed(() -> removeSplashTree(activity), 800);
    }

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

    private static void removeSplashTree(Activity activity) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup decorGroup = (ViewGroup) decor;
            View content = decorGroup.findViewById(android.R.id.content);

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
    // 悬浮广告扫描
    // ------------------------------------------------------------------
    private static void scheduleFloatingAdScan(Activity activity) {
        MAIN.postDelayed(() -> scanAndRemoveFloatingAds(activity), 1500);
        MAIN.postDelayed(() -> scanAndRemoveFloatingAds(activity), 3000);
        MAIN.postDelayed(() -> scanAndRemoveFloatingAds(activity), 6000);
    }

    private static void scanAndRemoveFloatingAds(Activity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup decorGroup = (ViewGroup) decor;
            View content = decorGroup.findViewById(android.R.id.content);

            // 1) decorView 直接子层（content 之外 → 悬浮层最可能的所在）
            for (int i = decorGroup.getChildCount() - 1; i >= 0; i--) {
                View child = decorGroup.getChildAt(i);
                if (child == content) continue;
                handleFloatingCandidate(child, decorGroup, i, "decor");
            }

            // 2) contentView 直接子层（overlay 型悬浮层通常也在这一层）
            if (content instanceof ViewGroup) {
                ViewGroup contentGroup = (ViewGroup) content;
                for (int i = contentGroup.getChildCount() - 1; i >= 0; i--) {
                    View child = contentGroup.getChildAt(i);
                    handleFloatingCandidate(child, contentGroup, i, "content");
                }
            }
        } catch (Throwable t) {
            note("floating scan error: " + t.getClass().getSimpleName());
        }
    }

    private static void handleFloatingCandidate(View view, ViewGroup parent, int index, String where) {
        try {
            String cls = view.getClass().getName();
            String id = resourceName(view);
            String lowerCls = cls.toLowerCase(Locale.ROOT);
            String lowerId = id.toLowerCase(Locale.ROOT);

            boolean explicit = matchesAny(lowerCls, lowerId, FLOATING_AD_KEYWORDS);
            boolean hint = matchesAny(lowerCls, lowerId, AD_HINT_KEYWORDS);

            if (explicit) {
                parent.removeViewAt(index);
                note("Removed floating ad (" + where + "): " + cls + " id=" + id);
            } else if (hint) {
                // 只打日志，不删除，供后续确认关键词
                note("Suspected ad view (" + where + "): " + cls
                        + " id=" + id
                        + " visibility=" + view.getVisibility()
                        + " size=" + view.getWidth() + "x" + view.getHeight());
            }
        } catch (Throwable t) {
            note("floating candidate error: " + t.getClass().getSimpleName());
        }
    }

    private static boolean matchesAny(String cls, String id, String[] keywords) {
        for (String kw : keywords) {
            if (cls.contains(kw) || id.contains(kw)) return true;
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
}

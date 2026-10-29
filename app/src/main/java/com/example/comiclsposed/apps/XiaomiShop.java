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
 * 开屏广告去除策略：
 *   displaySplashView 正常执行（保证启动流程走完），
 *   60/250/800ms 后多次尝试 dismiss + 反射 removeView，
 *   使 splash overlay 快速消失，主界面正常渲染。
 */
public final class XiaomiShop {

    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
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
        hookActivityCreate(module);
        hookDialogShow(module);
        hookActivityResume(module);
        note("Hooks installed; splash will be displayed then dismissed quickly.");
    }

    // ------------------------------------------------------------------
    // Activity.onCreate：提前挂 SplashPresenter Hook
    // ------------------------------------------------------------------
    private static void hookActivityCreate(XposedModule module) {
        try {
            Method create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(create).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity && isTarget((Activity) obj)) {
                    hookSplashManager(module, ((Activity) obj).getClassLoader());
                }
                Object result = chain.proceed();
                if (obj instanceof Activity && isTarget((Activity) obj)) {
                    note("Activity.onCreate: " + obj.getClass().getName());
                }
                return result;
            });
            note("Hook OK: Activity.onCreate");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Dialog.show：仅拦截 AdDialog 弹窗
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
    // Activity.onResume：兜底扫描 + 复制日志
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
                        scheduleSplashScan(activity, 0);
                        scheduleSplashScan(activity, 400);
                        scheduleSplashScan(activity, 1000);
                        scheduleCopy(activity, 1200);
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
    // Hook SplashPresenter.displaySplashView：
    // 关键改动：proceed 后再多次 dismiss，而不是 return null。
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
                note("Splash class unavailable " + className);
                continue;
            }
            for (Method method : cls.getDeclaredMethods()) {
                if (!"displaySplashView".equals(method.getName())) continue;
                try {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        // 让原始方法执行，保证 splash 的"显示/结束"回调链完整，主内容才能渲染
                        Object result = chain.proceed();
                        note("Splash displayed; scheduling dismiss: " + method.toGenericString());
                        MAIN.postDelayed(() -> dismissSplashSafely(receiver, "60ms"), 60);
                        MAIN.postDelayed(() -> dismissSplashSafely(receiver, "250ms"), 250);
                        MAIN.postDelayed(() -> dismissSplashSafely(receiver, "800ms"), 800);
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

    /**
     * 反射尝试关闭 splash：
     *  1) 优先调用无参 dismiss/hideSplash/removeSplash/closeSplash/skipSplash 方法；
     *  2) 兜底：从 SplashPresenter 的 View 字段里找到 splash 根容器，从 parent remove。
     */
    private static void dismissSplashSafely(Object presenter, String tag) {
        if (presenter == null) return;
        boolean invoked = false;

        Class<?> c = presenter.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (n.equals("dismiss")
                        || n.contains("dismisssplash")
                        || n.contains("hidesplash")
                        || n.contains("removesplash")
                        || n.contains("closesplash")
                        || n.contains("skipsplash")) {
                    try {
                        m.setAccessible(true);
                        m.invoke(presenter);
                        note("[" + tag + "] Invoked dismiss: " + m.getName());
                        invoked = true;
                    } catch (Throwable t) {
                        note("[" + tag + "] dismiss invoke fail: " + m.getName()
                                + " -> " + t.getClass().getSimpleName());
                    }
                }
            }
            c = c.getSuperclass();
        }

        // 兜底：反射 View 字段，从 parent 移除 splash 根容器
        c = presenter.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(presenter);
                    if (!(v instanceof View)) continue;
                    View view = (View) v;
                    if (view.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) view.getParent();
                        parent.removeView(view);
                        note("[" + tag + "] Removed splash view: " + f.getName()
                                + " class=" + view.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }

        if (!invoked) {
            note("[" + tag + "] no dismiss method matched, rely on removeView/scan fallback");
        }
    }

    // ------------------------------------------------------------------
    // 兜底扫描：只匹配 splash 根容器，不碰主界面
    // ------------------------------------------------------------------
    private static void scheduleSplashScan(Activity activity, long delayMs) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(() -> {
                try {
                    scanAndRemoveSplash(decor, new int[]{0});
                } catch (Throwable t) {
                    note("splash scan error: " + t.getClass().getSimpleName());
                }
            }, delayMs);
        } catch (Throwable ignored) {}
    }

    private static void scanAndRemoveSplash(View view, int[] count) {
        if (view == null || count[0]++ > MAX_NODES) return;
        if (isSplashRootContainer(view)) {
            if (view.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) view.getParent();
                parent.removeView(view);
                note("scan removed splash root: " + describe(view));
                copyClipboard(view.getContext());
            } else if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                note("scan GONE splash root: " + describe(view));
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && count[0] < MAX_NODES; i++) {
                scanAndRemoveSplash(group.getChildAt(i), count);
            }
        }
    }

    /** 只匹配 splash 的"根容器"，不碰 logo 等内部小元素（内部元素随根容器一起消失）。 */
    private static boolean isSplashRootContainer(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        String id = resourceName(view).toLowerCase(Locale.ROOT);
        return cls.contains("splashview")
                || cls.contains("splashroot")
                || cls.contains("splashlayout")
                || cls.contains("splashcontainer")
                || id.contains("splashview")
                || id.contains("splash_root")
                || id.contains("splashlayout")
                || id.contains("splash_container");
    }

    // ------------------------------------------------------------------
    // 工具
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

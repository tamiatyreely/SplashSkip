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
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Xiaomi Shop 5.54.0.20260811.r1 adapter.
 * Targets the confirmed AdDialog class and the video-cover container seen in
 * the user's runtime view-tree log. Copies a redacted rolling log to clipboard.
 */
public final class XiaomiShop {
    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
    private static final String MAIN_ACTIVITY = "com.xiaomi.shop2.activity.MainActivity";
    private static final String AD_DIALOG = "com.xiaomi.shop.plugin.homepage.utils.AdDialog";
    private static final int MAX_CHARS = 45000;
    private static final int MAX_NODES = 450;

    private static final StringBuilder LOG = new StringBuilder();
    private static final Set<Activity> SEEN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));
    private static volatile boolean installed;
    private static volatile boolean firstToastShown;
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
        hookSplashViewInsertion(module);
        note("Hooks installed; exact AdDialog blocker active; Xiaomi SplashActionView/resource-ID suppression active.");
        copyClipboardForProcess(module);
    }


    private static void hookActivityCreate(XposedModule module) {
        try {
            Method create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(create).intercept(chain -> {
                Object obj = chain.getThisObject();
                Object result = chain.proceed();
                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;
                    if (isTarget(activity)) {
                        String name = activity.getClass().getName();
                        String lower = name.toLowerCase(Locale.ROOT);
                        note("Activity.onCreate: " + name);
                        // Only auto-skip clearly named splash/ad-launch activities.
                        // MainActivity and all ordinary shopping pages are excluded.
                        boolean splashLike = lower.contains("splash")
                                || lower.contains("startup")
                                || lower.contains("launchad")
                                || lower.contains("adlaunch")
                                || lower.contains("advert");
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
                                note("Started MainActivity to skip splash-like Activity.");
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

    private static void hookDialogShow(XposedModule module) {
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            module.hook(show).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Dialog) {
                    Dialog dialog = (Dialog) obj;
                    if (isTarget(dialog.getContext())
                            && AD_DIALOG.equals(dialog.getClass().getName())) {
                        note("BLOCK Dialog.show: " + dialog.getClass().getName());
                        note("弹窗广告已在显示前拦截；未执行模拟点击。");
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
                        inspectAndSuppressKnownCover(activity);
                        scheduleSplashScan(activity, 0);
                        scheduleSplashScan(activity, 250);
                        scheduleSplashScan(activity, 800);
                        scheduleSplashScan(activity, 1600);
                        scheduleCopy(activity);
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onResume");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onResume: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Xiaomi Shop APK contains com.xiaomi.shop.view.SplashActionView and the
     * splash_action_view resource. The splash is rendered inside normal
     * activities, so there may be no separate SplashActivity to skip.
     */
    private static void hookSplashViewInsertion(XposedModule module) {
        int installedCount = 0;
        try {
            Method add = ViewGroup.class.getDeclaredMethod("addView", View.class);
            module.hook(add).intercept(chain -> {
                Object result = chain.proceed();
                Object arg = chain.getArg(0);
                if (arg instanceof View) {
                    View child = (View) arg;
                    if (isTarget(child.getContext())) {
                        child.post(() -> suppressSplashView(child, null));
                        child.postDelayed(() -> suppressSplashView(child, null), 180);
                    }
                }
                return result;
            });
            installedCount++;
            note("Hook OK: ViewGroup.addView(View) splash insertion filter");
        } catch (Throwable t) {
            note("Hook FAIL ViewGroup.addView(View): " + t.getClass().getSimpleName());
        }
        try {
            Method add = ViewGroup.class.getDeclaredMethod("addView", View.class, int.class);
            module.hook(add).intercept(chain -> {
                Object result = chain.proceed();
                Object arg = chain.getArg(0);
                if (arg instanceof View) {
                    View child = (View) arg;
                    if (isTarget(child.getContext())) {
                        child.post(() -> suppressSplashView(child, null));
                        child.postDelayed(() -> suppressSplashView(child, null), 180);
                    }
                }
                return result;
            });
            installedCount++;
            note("Hook OK: ViewGroup.addView(View,int) splash insertion filter");
        } catch (Throwable t) {
            note("Hook FAIL ViewGroup.addView(View,int): " + t.getClass().getSimpleName());
        }
        note("Splash view insertion hooks installed=" + installedCount);
    }

    private static void scheduleSplashScan(Activity activity, long delayMs) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.postDelayed(() -> scanForSplashViews(decor, activity, new int[]{0}), delayMs);
        } catch (Throwable ignored) {}
    }

    private static void scanForSplashViews(View view, Activity activity, int[] count) {
        if (view == null || count[0]++ > MAX_NODES) return;
        if (suppressSplashView(view, activity)) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && count[0] < MAX_NODES; i++) {
                scanForSplashViews(group.getChildAt(i), activity, count);
            }
        }
    }

    private static boolean suppressSplashView(View view, Activity activity) {
        if (view == null) return false;
        String cls = view.getClass().getName();
        String id = resourceName(view);
        boolean splash = "com.xiaomi.shop.view.SplashActionView".equals(cls)
                || cls.endsWith(".SplashActionView")
                || "splash_action_view".equals(id);
        if (!splash) return false;
        if (view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
            note("BLOCK Xiaomi splash view: " + cls + " id=" + id
                    + " activity=" + (activity == null ? "view-insertion" : activity.getClass().getName()));
            Context context = activity != null ? activity : view.getContext();
            copyClipboard(context);
        }
        return true;
    }

    /**
     * The supplied view-tree log contains the video-cover structure:
     * root -> bg_img / MiVideoPlayerView(coverVideo) / coverImage /
     * closeVideoBtn. Only hide the matching cover container if all signature
     * elements are present, to avoid hiding unrelated shopping UI.
     */
    private static void inspectAndSuppressKnownCover(Activity activity) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            int[] count = {0};
            scan(decor, activity, count);
        } catch (Throwable t) {
            note("cover scan error: " + t.getClass().getSimpleName());
        }
    }

    private static void scan(View view, Activity activity, int[] count) {
        if (view == null || count[0]++ > MAX_NODES) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int n = group.getChildCount();
            for (int i = 0; i < n && count[0] < MAX_NODES; i++) {
                View child = group.getChildAt(i);
                if (isKnownCoverRoot(child)) {
                    if (child.getVisibility() == View.VISIBLE) {
                        child.setVisibility(View.GONE);
                        note("BLOCK known video-cover root: " + describe(child));
                        copyClipboard(activity);
                    } else {
                        noteOnce("known video-cover root found but already hidden: " + describe(child));
                    }
                } else {
                    scan(child, activity, count);
                }
            }
        }
    }

    private static boolean isKnownCoverRoot(View view) {
        if (!(view instanceof ViewGroup)) return false;
        String cls = view.getClass().getName();
        // Runtime trace showed a FrameLayout named root containing coverVideo.
        if (!"android.widget.FrameLayout".equals(cls)) return false;
        String idName = resourceName(view);
        if (!"root".equals(idName)) return false;
        return hasDescendantSignature((ViewGroup) view, 0, new int[]{0});
    }

    private static boolean hasDescendantSignature(ViewGroup group, int depth, int[] nodes) {
        if (depth > 7 || nodes[0]++ > 70) return false;
        boolean video = false, close = false, cover = false, bg = false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String id = resourceName(child);
            String cn = child.getClass().getName();
            if ("coverVideo".equals(id) || cn.equals("com.xiaomi.shop.lib.video2.base.MiVideoPlayerView")) video = true;
            if ("closeVideoBtn".equals(id) || "closeBtn".equals(id)) close = true;
            if ("coverImage".equals(id)) cover = true;
            if ("bg_img".equals(id)) bg = true;
            if (child instanceof ViewGroup && hasDescendantSignature((ViewGroup) child, depth + 1, nodes)) {
                video = true;
            }
        }
        return video && close && cover && bg;
    }

    private static void scheduleCopy(Activity activity) {
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.postDelayed(() -> copyClipboard(activity), 500);
        } catch (Throwable ignored) {}
    }

    private static void copyClipboardForProcess(XposedModule module) {
        // Clipboard is copied when an Activity resumes or a target popup is intercepted.
        note("Clipboard policy: copy on activity resume and popup interception.");
    }

    private static boolean isTarget(Context context) {
        try { return context != null && PACKAGE.equals(context.getPackageName()); }
        catch (Throwable ignored) { return false; }
    }

    private static String safeIntent(Activity a) {
        try {
            if (a.getIntent() == null) return "null";
            return "action=" + a.getIntent().getAction()
                    + ", data=" + (a.getIntent().getData() == null ? "null" : scrubUrl(a.getIntent().getData().toString()));
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
        return v.getClass().getName() + " id=" + resourceName(v) + " visibility=" + v.getVisibility();
    }

    private static synchronized void note(String message) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new java.util.Date()) + " " + redact(message);
        LOG.append(line).append('\n');
        if (LOG.length() > MAX_CHARS) LOG.delete(0, LOG.length() - MAX_CHARS);
        Log.i(TAG, line);
    }

    private static synchronized void noteOnce(String message) {
        String needle = redact(message);
        if (LOG.indexOf(needle) < 0) note(message);
    }

    private static String redact(String s) {
        if (s == null) return "null";
        return s.replaceAll("(?i)(token|cookie|session|authorization|password|phone|mobile)\\s*[:=]\\s*[^,; ]+", "$1=<hidden>");
    }

    private static String scrubUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?<query-hidden>" : url;
    }

    private static void copyClipboard(Context context) {
        if (!isTarget(context)) return;
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            String text;
            synchronized (XiaomiShop.class) { text = LOG.toString(); }
            manager.setPrimaryClip(ClipData.newPlainText("SplashSkip Xiaomi Shop log", text));
            if (!firstToastShown && context instanceof Activity) {
                firstToastShown = true;
                android.widget.Toast.makeText(context, "SplashSkip：小米商城日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "clipboard copy failed: " + t.getClass().getSimpleName());
        }
    }
}

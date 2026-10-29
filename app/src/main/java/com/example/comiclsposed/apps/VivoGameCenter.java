package com.example.comiclsposed.apps;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * vivo 游戏中心去广告模块（功能版 + 内置诊断 + 剪贴板回传）。
 *
 * 目标包名：com.vivo.game
 *
 * 工作机制：
 * 1. 全局记录每个被创建的 Activity。
 * 2. 向 LogoActivity 的 Intent 注入常见“跳过开屏”标志（尝试性）。
 * 3. Hook LogoActivity / SimpleSplashActivity 的 onResume，
 *    延迟强制 finish 并跳转主界面。
 * 4. 全局 Hook Activity.startActivity，拦截疑似广告 Activity 的启动。
 * 5. 拦截 VivoADSDK 的所有 init 重载，使广告 SDK 无法初始化。
 *
 * 日志同时输出到 Logcat 并自动复制到剪贴板（防抖合并）。
 * 拿到剪贴板内容后直接粘出来即可定位问题。
 */
public final class VivoGameCenter {

    private static final String TAG = "VivoGameFilter";

    private static final String PACKAGE = "com.vivo.game";
    private static final String LOGO_ACTIVITY =
            "com.vivo.game.ui.LogoActivity";
    private static final String SIMPLE_SPLASH_ACTIVITY =
            "com.vivo.game.splash.SimpleSplashActivity";
    private static final String MAIN_ACTIVITY =
            "com.vivo.game.ui.GameTabActivity";
    private static final String AD_SDK =
            "com.vivo.adsdk.common.VivoADSDK";

    /** 在 Splash 页 onResume 之后延迟多久强制跳过，单位毫秒 */
    private static final long LOGO_SKIP_DELAY_MS = 300L;

    /** 剪贴板合并窗口：日志产生后延迟多久写一次剪贴板，单位毫秒 */
    private static final long CLIPBOARD_DEBOUNCE_MS = 900L;

    private static final int MAX_LOG_LENGTH = 28000;

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static final StringBuilder DIAG_LOG = new StringBuilder();

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    /** 已经安排过跳过流程的 Splash 类 Activity（避免重复 finish） */
    private static final Set<Activity> HANDLED_SPLASH_ACTIVITIES =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean installed;
    private static volatile Activity currentActivity;

    private VivoGameCenter() {
    }

    /**
     * 模块入口。
     */
    public static synchronized void install(XposedModule module) {
        if (installed) {
            return;
        }
        installed = true;

        diag("=== Vivo 游戏中心去广告模块启动 ===");
        diag("目标包名: " + PACKAGE);
        diag("跳过延迟: " + LOGO_SKIP_DELAY_MS + "ms");

        hookAllActivityOnCreate(module);

        hookSplashActivityResume(module,
                VivoGameCenter.class.getClassLoader(),
                LOGO_ACTIVITY);
        hookSplashActivityResume(module,
                VivoGameCenter.class.getClassLoader(),
                SIMPLE_SPLASH_ACTIVITY);

        hookStartActivity(module);
        hookAdSdkInit(module);

        diag("=== 所有 Hook 安装完成 ===");
    }

    // ============================================================
    //  1. 全局 Activity.onCreate 诊断 + Logo 页 Intent 注入
    // ============================================================

    private static void hookAllActivityOnCreate(XposedModule module) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", Bundle.class);

            module.hook(onCreate).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity act = (Activity) obj;
                    try {
                        if (PACKAGE.equals(act.getPackageName())) {
                            String name = act.getClass().getName();
                            diag("Activity: " + name);
                            currentActivity = act;

                            if (LOGO_ACTIVITY.equals(name)) {
                                injectSkipExtras(act);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
            diag("全局 Activity.onCreate Hook OK");

        } catch (Throwable e) {
            diag("全局 Activity.onCreate Hook 失败: " + e);
        }
    }

    private static void injectSkipExtras(Activity act) {
        try {
            Intent intent = act.getIntent();
            if (intent == null) {
                return;
            }
            intent.putExtra("skip_splash", true);
            intent.putExtra("show_splash_ad", false);
            intent.putExtra("need_splash", false);
            intent.putExtra("from_shortcut", true);
            intent.putExtra("is_from_launcher", true);
            diag("已向 LogoActivity 注入跳过标志");
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    //  2. Splash 类 Activity 延迟跳过
    // ============================================================

    private static void hookSplashActivityResume(
            XposedModule module,
            ClassLoader cl,
            String className
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);

            Method target;
            try {
                target = cls.getDeclaredMethod("onResume");
            } catch (NoSuchMethodException e) {
                diag(className + " 无 onResume，改 Hook onStart");
                target = cls.getDeclaredMethod("onStart");
            }

            final Method finalMethod = target;

            module.hook(finalMethod).intercept(chain -> {
                Object result = chain.proceed();

                Object thisObj = chain.getThisObject();
                if (thisObj instanceof Activity) {
                    Activity act = (Activity) thisObj;
                    if (!HANDLED_SPLASH_ACTIVITIES.contains(act)) {
                        HANDLED_SPLASH_ACTIVITIES.add(act);
                        scheduleSkip(act);
                    }
                }
                return result;
            });
            diag(className + "." + finalMethod.getName() + " Hook OK");

        } catch (Throwable e) {
            diag("Hook " + className + " 失败: " + e);
        }
    }

    private static void scheduleSkip(Activity act) {
        MAIN_HANDLER.postDelayed(() -> {
            try {
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }

                diag("延迟触发跳过: "
                        + act.getClass().getSimpleName());

                Intent intent = new Intent();
                intent.setClassName(PACKAGE, MAIN_ACTIVITY);
                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                act.startActivity(intent);
                act.finish();

                diag("已跳过 "
                        + act.getClass().getSimpleName()
                        + " 并启动主界面");

            } catch (Throwable e) {
                diag("跳过失败: " + e);
            }
        }, LOGO_SKIP_DELAY_MS);
    }

    // ============================================================
    //  3. 拦截广告 Activity 启动
    // ============================================================

    private static void hookStartActivity(XposedModule module) {
        try {
            Method startActivity = Activity.class.getDeclaredMethod(
                    "startActivity", Intent.class);

            module.hook(startActivity).intercept(chain -> {
                try {
                    Intent intent = (Intent) chain.getArgs().get(0);
                    if (isAdIntent(intent)) {
                        diag("拦截广告 Activity 启动: "
                                + intent.getComponent().getClassName());
                        return null;
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            diag("Activity.startActivity Hook OK");

        } catch (Throwable e) {
            diag("startActivity Hook 失败: " + e);
        }
    }

    private static boolean isAdIntent(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return false;
        }
        String cls = intent.getComponent().getClassName();
        if (cls == null) {
            return false;
        }

        if (LOGO_ACTIVITY.equals(cls)
                || SIMPLE_SPLASH_ACTIVITY.equals(cls)
                || MAIN_ACTIVITY.equals(cls)) {
            return false;
        }

        String lower = cls.toLowerCase(Locale.ROOT);

        if (lower.contains("adsdk")
                || lower.contains("interstitial")
                || lower.contains("splashad")
                || lower.contains("adactivity")
                || lower.contains("landingpage")
                || lower.contains("reward")
                || lower.contains("fullscreenad")
                || lower.contains("adfullscreen")
                || lower.contains("ad.view")) {
            return true;
        }
        return false;
    }

    // ============================================================
    //  4. 拦截 VivoADSDK init
    // ============================================================

    private static void hookAdSdkInit(XposedModule module) {
        try {
            Class<?> sdkClass = Class.forName(
                    AD_SDK, false,
                    VivoGameCenter.class.getClassLoader());

            int count = 0;
            for (Method m : sdkClass.getDeclaredMethods()) {
                if (!"init".equals(m.getName())) {
                    continue;
                }
                if (Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (!HOOKED_METHODS.add(m.toGenericString())) {
                    continue;
                }

                module.hook(m).intercept(chain -> {
                    diag("拦截 VivoADSDK.init: "
                            + m.toGenericString());

                    Class<?> ret = m.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == short.class) return (short) 0;
                    if (ret == byte.class) return (byte) 0;
                    if (ret == char.class) return '\0';
                    if (ret == float.class) return 0f;
                    if (ret == double.class) return 0d;
                    return null;
                });
                count++;
            }
            diag("VivoADSDK init Hook OK, 数量: " + count);

        } catch (Throwable e) {
            diag("VivoADSDK Hook 失败: " + e);
        }
    }

    // ============================================================
    //  日志 + 剪贴板自动复制
    // ============================================================

    private static void diag(String message) {
        String line = "[VIVO_FIX] " + message;
        Log.i(TAG, line);

        synchronized (DIAG_LOG) {
            DIAG_LOG.append(line).append('\n');
            if (DIAG_LOG.length() > MAX_LOG_LENGTH) {
                DIAG_LOG.delete(0,
                        DIAG_LOG.length() - MAX_LOG_LENGTH / 2);
            }
        }

        scheduleClipboardCopy();
    }

    /** 防抖：短时间内多次 diag 只触发一次复制 */
    private static void scheduleClipboardCopy() {
        MAIN_HANDLER.removeCallbacks(CLIPBOARD_COPY_RUNNABLE);
        MAIN_HANDLER.postDelayed(
                CLIPBOARD_COPY_RUNNABLE,
                CLIPBOARD_DEBOUNCE_MS);
    }

    private static final Runnable CLIPBOARD_COPY_RUNNABLE = () -> {
        Activity activity = currentActivity;
        if (activity == null) {
            return;
        }
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            String snapshot;
            synchronized (DIAG_LOG) {
                snapshot = DIAG_LOG.toString();
            }
            if (snapshot.isEmpty()) {
                return;
            }

            ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(
                            Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                                "Vivo 游戏中心诊断日志",
                                snapshot));
            }
        } catch (Throwable error) {
            Log.e(TAG, "复制诊断日志失败", error);
        }
    };
}

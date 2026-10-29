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
 * vivo 游戏中心去广告模块（彻底消除开屏闪现版）。
 *
 * 关键调整：
 * 1. 跳过延迟设为 0，onResume 后立即 finish，不给广告渲染时间。
 * 2. 调用 overridePendingTransition(0, 0) 禁用切换动画，消除视觉闪烁。
 * 3. 先 startActivity 再 finish，保证过渡平滑。
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

    /** 关键修改：延迟设为 0，立即跳过 */
    private static final long LOGO_SKIP_DELAY_MS = 0L;

    private static final long CLIPBOARD_DEBOUNCE_MS = 900L;
    private static final int MAX_LOG_LENGTH = 28000;

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static final StringBuilder DIAG_LOG = new StringBuilder();

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Activity> HANDLED_SPLASH_ACTIVITIES =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean installed;
    private static volatile Activity currentActivity;
    private static volatile boolean adSdkHooked;

    private VivoGameCenter() {
    }

    public static synchronized void install(XposedModule module) {
        if (installed) {
            return;
        }
        installed = true;

        diag("=== Vivo 游戏中心去广告模块启动（彻底消除闪现版）===");
        diag("目标包名: " + PACKAGE);
        diag("跳过延迟: " + LOGO_SKIP_DELAY_MS + "ms (立即跳过)");

        hookAllActivityOnCreate(module);
        hookAllActivityOnResume(module);
        hookStartActivity(module);

        diag("=== 所有 Hook 安装完成 ===");
    }

    // ============================================================
    //  1. 全局 Activity.onCreate：记录 + Logo 页注入 + 动态 Hook SDK
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
                                hookAdSdkInitDynamic(
                                        module,
                                        act.getClassLoader());
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

    private static void hookAdSdkInitDynamic(
            XposedModule module,
            ClassLoader targetCl
    ) {
        if (adSdkHooked) {
            return;
        }
        adSdkHooked = true;

        try {
            Class<?> sdkClass = Class.forName(
                    AD_SDK, false, targetCl);

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
            diag("动态 Hook VivoADSDK.init 完成，数量: " + count);

            if (count == 0) {
                diag("警告：VivoADSDK 的 init 均为抽象方法，"
                        + "实际实现类未知，SDK 初始化可能未被拦截");
            }

        } catch (Throwable e) {
            diag("动态 Hook VivoADSDK 失败: " + e);
        }
    }

    // ============================================================
    //  2. 全局 Activity.onResume：立即跳过 Splash 页
    // ============================================================

    private static void hookAllActivityOnResume(XposedModule module) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");

            module.hook(onResume).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity act = (Activity) obj;
                    try {
                        if (PACKAGE.equals(act.getPackageName())) {
                            String name = act.getClass().getName();

                            if (LOGO_ACTIVITY.equals(name)
                                    || SIMPLE_SPLASH_ACTIVITY.equals(name)) {
                                diag("Splash onResume: " + name);

                                if (!HANDLED_SPLASH_ACTIVITIES
                                        .contains(act)) {
                                    HANDLED_SPLASH_ACTIVITIES.add(act);
                                    scheduleSkip(act);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
            diag("全局 Activity.onResume Hook OK");

        } catch (Throwable e) {
            diag("全局 Activity.onResume Hook 失败: " + e);
        }
    }

    private static void scheduleSkip(Activity act) {
        // 延迟为 0，直接在当前消息循环中执行
        MAIN_HANDLER.post(() -> {
            try {
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }

                diag("立即触发跳过: "
                        + act.getClass().getSimpleName());

                Intent intent = new Intent();
                intent.setClassName(PACKAGE, MAIN_ACTIVITY);
                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                // 先启动主界面，再结束当前页，避免白屏
                act.startActivity(intent);
                act.finish();

                // 关键：禁用 Activity 切换动画，消除闪一下
                act.overridePendingTransition(0, 0);

                diag("已跳过 "
                        + act.getClass().getSimpleName()
                        + " 并启动主界面（无动画）");

            } catch (Throwable e) {
                diag("跳过失败: " + e);
            }
        });
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

        return lower.contains("adsdk")
                || lower.contains("interstitial")
                || lower.contains("splashad")
                || lower.contains("adactivity")
                || lower.contains("landingpage")
                || lower.contains("reward")
                || lower.contains("fullscreenad")
                || lower.contains("adfullscreen")
                || lower.contains("ad.view");
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

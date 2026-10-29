package com.example.comiclsposed.apps;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
 * SplashSkip —— vivo 游戏中心开屏跳过模块。
 *
 * 核心策略：
 * 在 LogoActivity / SimpleSplashActivity 的 onCreate 返回之后
 * 立即 startActivity + finish，此时窗口尚未 attach 到 WMS，
 * 因此不会绘制任何一帧广告，彻底消除“闪一下”。
 *
 * onResume 保留兜底 Hook，正常情况下不会触发。
 */
public final class SplashSkip {

    private static final String TAG = "SplashSkip";

    private static final String PACKAGE = "com.vivo.game";
    private static final String LOGO_ACTIVITY =
            "com.vivo.game.ui.LogoActivity";
    private static final String SIMPLE_SPLASH_ACTIVITY =
            "com.vivo.game.splash.SimpleSplashActivity";
    private static final String MAIN_ACTIVITY =
            "com.vivo.game.ui.GameTabActivity";
    private static final String AD_SDK =
            "com.vivo.adsdk.common.VivoADSDK";

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Activity> HANDLED_ACTIVITIES =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean installed;
    private static volatile boolean adSdkHooked;

    private SplashSkip() {
    }

    public static synchronized void install(XposedModule module) {
        if (installed) {
            return;
        }
        installed = true;

        diag("=== SplashSkip 模块启动 ===");
        diag("目标包名: " + PACKAGE);

        hookActivityOnCreate(module);
        hookActivityOnResume(module);
        hookStartActivity(module);

        diag("=== 所有 Hook 安装完成 ===");
    }

    // ============================================================
    //  1. Activity.onCreate：核心跳过点
    // ============================================================

    private static void hookActivityOnCreate(XposedModule module) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate", Bundle.class);

            module.hook(onCreate).intercept(chain -> {
                Activity act = null;
                String name = null;

                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    act = (Activity) obj;
                    try {
                        if (PACKAGE.equals(act.getPackageName())) {
                            name = act.getClass().getName();
                            diag("Activity: " + name);

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

                // 先让原始 onCreate 完整执行
                Object result = chain.proceed();

                // onCreate 返回后立即跳过，此时窗口尚未 attach 到 WMS
                if (act != null && name != null
                        && (LOGO_ACTIVITY.equals(name)
                                || SIMPLE_SPLASH_ACTIVITY.equals(name))) {
                    if (HANDLED_ACTIVITIES.add(act)) {
                        skipImmediately(act, name);
                    }
                }

                return result;
            });
            diag("Activity.onCreate Hook OK");

        } catch (Throwable e) {
            diag("Activity.onCreate Hook 失败: " + e);
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
            diag("VivoADSDK.init Hook 完成，数量: " + count);

        } catch (Throwable e) {
            diag("Hook VivoADSDK 失败: " + e);
        }
    }

    /**
     * 在 onCreate 返回后立即执行跳过。
     * 此时 Activity 窗口未 attach，不会产生任何可见帧。
     */
    private static void skipImmediately(Activity act, String name) {
        try {
            if (act.isFinishing() || act.isDestroyed()) {
                diag("跳过被忽略（已 finishing）: " + name);
                return;
            }

            diag("立即跳过: " + name);

            Intent intent = new Intent();
            intent.setClassName(PACKAGE, MAIN_ACTIVITY);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // 先启动主界面，再 finish 当前页，禁用切换动画
            act.startActivity(intent);
            act.finish();
            act.overridePendingTransition(0, 0);

            diag("已跳过 " + name + " 并启动主界面");

        } catch (Throwable e) {
            diag("跳过失败: " + e);
        }
    }

    // ============================================================
    //  2. Activity.onResume：兜底（正常不会触发）
    // ============================================================

    private static void hookActivityOnResume(XposedModule module) {
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
                                diag("Splash onResume（兜底）: " + name);
                                if (HANDLED_ACTIVITIES.add(act)) {
                                    skipImmediately(act, name);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
            diag("Activity.onResume Hook OK");

        } catch (Throwable e) {
            diag("Activity.onResume Hook 失败: " + e);
        }
    }

    // ============================================================
    //  3. 拦截广告 Activity 启动（弹窗广告）
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
    //  日志
    // ============================================================

    private static void diag(String message) {
        Log.i(TAG, "[SplashSkip] " + message);
    }
}

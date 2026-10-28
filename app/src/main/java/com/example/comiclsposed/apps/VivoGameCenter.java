package com.example.comiclsposed.apps;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * vivo 游戏中心专用处理。
 *
 * 不模拟点击跳过按钮。
 * 尝试在 LogoActivity 启动前 Hook VivoADSDK 中名称明确的广告方法。
 *
 * 注意：实际 SDK 方法名可能被混淆；本方案需要结合运行日志验证。
 */
public final class VivoGameCenter {

    private static final String TAG = "VivoGameFilter";

    private static final String PACKAGE = "com.vivo.game";

    private static final String SPLASH_ACTIVITY =
            "com.vivo.game.ui.LogoActivity";

    private static final String AD_SDK =
            "com.vivo.adsdk.common.VivoADSDK";

    private static volatile boolean installed;

    private static volatile Activity currentActivity;

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    private VivoGameCenter() {
    }

    public static synchronized void install(XposedModule module) {
        if (installed) {
            return;
        }

        installed = true;

        try {
            Method onCreate = Activity.class.getDeclaredMethod(
                    "onCreate",
                    Bundle.class
            );

            module.hook(onCreate).intercept(chain -> {
                Object obj = chain.getThisObject();

                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;

                    if (PACKAGE.equals(activity.getPackageName())) {
                        currentActivity = activity;

                        log("进入 Activity: "
                                + activity.getClass().getName());

                        /*
                         * 必须在原始 onCreate 执行前尝试 Hook。
                         * 如果广告 SDK 在 onCreate 内调用，这样才有机会拦截。
                         */
                        if (SPLASH_ACTIVITY.equals(
                                activity.getClass().getName())) {
                            hookAdSdk(module, activity);
                        }
                    }
                }

                return chain.proceed();
            });

            log("Activity.onCreate Hook 已安装");

        } catch (Throwable e) {
            logError("安装 onCreate Hook 失败", e);
        }

        try {
            Method onResume =
                    Activity.class.getDeclaredMethod("onResume");

            module.hook(onResume).intercept(chain -> {
                Object obj = chain.getThisObject();

                if (obj instanceof Activity) {
                    Activity activity = (Activity) obj;

                    if (PACKAGE.equals(activity.getPackageName())) {
                        currentActivity = activity;

                        log("页面恢复: "
                                + activity.getClass().getName());
                    }
                }

                return chain.proceed();
            });

            log("Activity.onResume Hook 已安装");

        } catch (Throwable e) {
            logError("安装 onResume Hook 失败", e);
        }

        log("vivo 游戏中心广告过滤模块已启动");
    }

    /**
     * 在 LogoActivity 原始 onCreate 执行前尝试 Hook 广告 SDK。
     */
    private static void hookAdSdk(
            XposedModule module,
            Activity activity
    ) {
        try {
            ClassLoader loader = activity.getClassLoader();

            Class<?> sdkClass = Class.forName(
                    AD_SDK,
                    false,
                    loader
            );

            int hooked = 0;

            for (Method method : sdkClass.getDeclaredMethods()) {
                if (!isCandidateAdMethod(method)) {
                    continue;
                }

                String signature = method.toGenericString();

                if (!HOOKED_METHODS.add(signature)) {
                    continue;
                }

                try {
                    module.hook(method).intercept(chain -> {
                        Activity current = currentActivity;

                        /*
                         * 只在开屏 Activity 是当前页面时拦截。
                         * 首页中的同名广告调用不会被这段代码直接拦截。
                         */
                        if (current != null
                                && !current.isFinishing()
                                && SPLASH_ACTIVITY.equals(
                                        current.getClass().getName())) {

                            log("拦截候选开屏广告方法: "
                                    + signature);

                            return defaultValue(
                                    method.getReturnType()
                            );
                        }

                        return chain.proceed();
                    });

                    hooked++;

                    log("已 Hook 候选方法: " + signature);

                } catch (Throwable e) {
                    logError("Hook 方法失败: " + signature, e);
                }
            }

            log("VivoADSDK 候选方法 Hook 数量: " + hooked);

            if (hooked == 0) {
                log("未找到名称明确匹配的广告方法；"
                        + "该版本可能使用其他类名或混淆方法名");
            }

        } catch (ClassNotFoundException e) {
            log("未找到广告 SDK 类: " + AD_SDK);

        } catch (Throwable e) {
            logError("处理广告 SDK 失败", e);
        }
    }

    /**
     * 仅匹配广告加载、请求、展示相关方法。
     *
     * 不拦截 SDK 的初始化、配置、销毁等所有其他方法。
     */
    private static boolean isCandidateAdMethod(Method method) {
        String name = method.getName()
                .toLowerCase(Locale.ROOT);

        boolean hasAdWord =
                name.contains("ad")
                        || name.contains("splash");

        boolean hasActionWord =
                name.contains("load")
                        || name.contains("request")
                        || name.contains("fetch")
                        || name.contains("show")
                        || name.contains("display")
                        || name.contains("present")
                        || name.contains("render");

        /*
         * 避免将明显属于初始化、销毁、配置的函数作为候选。
         */
        boolean excluded =
                name.contains("init")
                        || name.contains("destroy")
                        || name.contains("release")
                        || name.contains("config")
                        || name.contains("listener");

        return hasAdWord && hasActionWord && !excluded;
    }

    /**
     * 返回对应类型的默认值，避免原始方法执行。
     *
     * 警告：如果被拦截的方法返回值是后续启动流程必须使用的对象，
     * 返回 null 可能造成异常，因此必须通过日志验证命中的方法。
     */
    private static Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) {
            return null;
        }

        if (!type.isPrimitive()) {
            return null;
        }

        if (type == Boolean.TYPE) {
            return false;
        }

        if (type == Byte.TYPE) {
            return (byte) 0;
        }

        if (type == Short.TYPE) {
            return (short) 0;
        }

        if (type == Integer.TYPE) {
            return 0;
        }

        if (type == Long.TYPE) {
            return 0L;
        }

        if (type == Float.TYPE) {
            return 0F;
        }

        if (type == Double.TYPE) {
            return 0D;
        }

        if (type == Character.TYPE) {
            return '\0';
        }

        return null;
    }

    private static String appVersion(Activity activity) {
        try {
            PackageInfo info =
                    activity.getPackageManager().getPackageInfo(
                            PACKAGE,
                            0
                    );

            return info.versionName == null
                    ? "unknown"
                    : info.versionName;

        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void log(String message) {
        Log.i(TAG, "[VIVO_FILTER] " + message);
    }

    private static void logError(
            String message,
            Throwable error
    ) {
        Log.e(
                TAG,
                "[VIVO_FILTER] " + message,
                error
        );
    }
}

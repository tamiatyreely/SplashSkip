package com.example.comiclsposed.apps;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * vivo 游戏中心诊断模块。
 *
 * 目标包名：com.vivo.game
 *
 * 本版本功能：
 * 1. 记录 Activity 生命周期。
 * 2. 枚举开屏 Activity 和广告 SDK 的方法、字段。
 * 3. 对部分疑似广告相关方法进行只记录、不拦截的 Hook。
 * 4. 将诊断日志自动复制到剪贴板。
 *
 * 注意：
 * 本版本是诊断版本，不会主动跳过广告、不模拟点击、
 * 不关闭 Activity，也不会阻止广告 SDK 方法执行。
 */
public final class VivoGameCenter {

    private static final String TAG = "VivoGameFilter";

    private static final String PACKAGE = "com.vivo.game";

    private static final String LOGO_ACTIVITY =
            "com.vivo.game.ui.LogoActivity";

    private static final String SIMPLE_SPLASH_ACTIVITY =
            "com.vivo.game.splash.SimpleSplashActivity";

    private static final String AD_SDK =
            "com.vivo.adsdk.common.VivoADSDK";

    private static final int MAX_LOG_LENGTH = 28000;

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static final StringBuilder DIAG_LOG =
            new StringBuilder();

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private static volatile Activity currentActivity;

    private static volatile boolean sdkInspected;

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

        diag("================================");
        diag("vivo 游戏中心诊断模块启动");
        diag("目标包名: " + PACKAGE);
        diag("诊断模式: 只记录，不拦截");
        diag("================================");

        hookActivityOnCreate(module);
        hookActivityOnResume(module);
        hookActivityOnDestroy(module);

        diag("生命周期 Hook 安装流程结束");
    }

    /**
     * 记录 Activity.onCreate。
     *
     * 在原始 onCreate 执行前，先检查开屏类和 SDK。
     */
    private static void hookActivityOnCreate(XposedModule module) {
        try {
            Method method = Activity.class.getDeclaredMethod(
                    "onCreate",
                    Bundle.class
            );

            module.hook(method).intercept(chain -> {
                Object object = chain.getThisObject();

                if (object instanceof Activity) {
                    Activity activity = (Activity) object;

                    if (isTargetActivity(activity)) {
                        currentActivity = activity;

                        diag("onCreate: "
                                + activity.getClass().getName());

                        diag("版本: " + appVersion(activity));

                        if (LOGO_ACTIVITY.equals(
                                activity.getClass().getName())) {
                            inspectSplashClasses(
                                    module,
                                    activity.getClassLoader()
                            );
                        }
                    }
                }

                return chain.proceed();
            });

            diag("Activity.onCreate Hook 安装成功");

        } catch (Throwable error) {
            diagError("安装 onCreate Hook 失败", error);
        }
    }

    /**
     * 记录 Activity.onResume。
     */
    private static void hookActivityOnResume(XposedModule module) {
        try {
            Method method =
                    Activity.class.getDeclaredMethod("onResume");

            module.hook(method).intercept(chain -> {
                Object object = chain.getThisObject();

                if (object instanceof Activity) {
                    Activity activity = (Activity) object;

                    if (isTargetActivity(activity)) {
                        currentActivity = activity;

                        diag("onResume: "
                                + activity.getClass().getName());
                    }
                }

                return chain.proceed();
            });

            diag("Activity.onResume Hook 安装成功");

        } catch (Throwable error) {
            diagError("安装 onResume Hook 失败", error);
        }
    }

    /**
     * 记录 Activity.onDestroy。
     */
    private static void hookActivityOnDestroy(XposedModule module) {
        try {
            Method method =
                    Activity.class.getDeclaredMethod("onDestroy");

            module.hook(method).intercept(chain -> {
                Object object = chain.getThisObject();

                if (object instanceof Activity) {
                    Activity activity = (Activity) object;

                    if (isTargetActivity(activity)) {
                        diag("onDestroy: "
                                + activity.getClass().getName());

                        if (currentActivity == activity) {
                            currentActivity = null;
                        }
                    }
                }

                return chain.proceed();
            });

            diag("Activity.onDestroy Hook 安装成功");

        } catch (Throwable error) {
            diagError("安装 onDestroy Hook 失败", error);
        }
    }

    /**
     * 检查开屏 Activity、备用开屏 Activity 和广告 SDK。
     */
    private static void inspectSplashClasses(
            XposedModule module,
            ClassLoader loader
    ) {
        if (sdkInspected) {
            diag("开屏类已检查过，跳过重复枚举");
            return;
        }

        sdkInspected = true;

        inspectClass(
                module,
                loader,
                LOGO_ACTIVITY,
                true
        );

        inspectClass(
                module,
                loader,
                SIMPLE_SPLASH_ACTIVITY,
                true
        );

        inspectClass(
                module,
                loader,
                AD_SDK,
                true
        );
    }

    /**
     * 输出指定类的方法和字段。
     *
     * 对疑似相关方法安装观察型 Hook。
     * Hook 内仍然调用原方法，不修改返回值。
     */
    private static void inspectClass(
            XposedModule module,
            ClassLoader loader,
            String className,
            boolean hookCandidates
    ) {
        diag("--------------------------------");
        diag("开始检查类: " + className);

        try {
            Class<?> clazz = Class.forName(
                    className,
                    false,
                    loader
            );

            diag("找到类: " + clazz.getName());

            Method[] methods = clazz.getDeclaredMethods();

            diag("声明方法数量: " + methods.length);

            for (Method method : methods) {
                String signature;

                try {
                    signature = method.toGenericString();
                } catch (Throwable ignored) {
                    signature = method.getName();
                }

                diag("方法: " + signature);

                if (hookCandidates && isTraceCandidate(method)) {
                    hookTraceMethod(module, method);
                }
            }

            Field[] fields = clazz.getDeclaredFields();

            diag("声明字段数量: " + fields.length);

            for (Field field : fields) {
                try {
                    diag("字段: " + field.toGenericString());
                } catch (Throwable ignored) {
                    diag("字段名: " + field.getName());
                }
            }

        } catch (ClassNotFoundException error) {
            diag("未找到类: " + className);

        } catch (Throwable error) {
            diagError("检查类失败: " + className, error);
        }
    }

    /**
     * 选择疑似与开屏、广告请求或展示有关的方法。
     *
     * 这里只决定是否记录调用，不决定是否拦截。
     */
    private static boolean isTraceCandidate(Method method) {
        int modifiers = method.getModifiers();

        if (Modifier.isAbstract(modifiers)
                || Modifier.isNative(modifiers)) {
            return false;
        }

        String name = method.getName()
                .toLowerCase(Locale.ROOT);

        return name.contains("ad")
                || name.contains("splash")
                || name.contains("launch")
                || name.contains("request")
                || name.contains("load")
                || name.contains("fetch")
                || name.contains("show")
                || name.contains("display")
                || name.contains("present")
                || name.contains("render")
                || name.contains("preload")
                || name.contains("init")
                || name.contains("start");
    }

    /**
     * 观察方法调用。
     *
     * 重要：始终执行原方法，不更改其返回值。
     */
    private static void hookTraceMethod(
            XposedModule module,
            Method method
    ) {
        final String signature;

        try {
            signature = method.toGenericString();
        } catch (Throwable ignored) {
            return;
        }

        if (!HOOKED_METHODS.add(signature)) {
            return;
        }

        try {
            module.hook(method).intercept(chain -> {
                Activity activity = currentActivity;

                boolean inTargetProcess =
                        activity != null
                                && PACKAGE.equals(
                                        activity.getPackageName());

                if (inTargetProcess) {
                    diag(">>> 方法调用: " + signature);
                }

                Object result = chain.proceed();

                if (inTargetProcess) {
                    diag("<<< 方法返回: " + method.getName()
                            + ", 返回类型="
                            + method.getReturnType().getName()
                            + ", 返回值="
                            + describeResult(result));
                }

                return result;
            });

            diag("观察 Hook 安装成功: " + signature);

        } catch (Throwable error) {
            diagError("观察 Hook 安装失败: " + signature, error);
        }
    }

    /**
     * 避免直接调用对象的 toString() 造成额外副作用。
     */
    private static String describeResult(Object result) {
        if (result == null) {
            return "null";
        }

        if (result instanceof String
                || result instanceof Number
                || result instanceof Boolean
                || result instanceof Character) {
            String value = String.valueOf(result);

            if (value.length() > 160) {
                value = value.substring(0, 160) + "...";
            }

            return value;
        }

        return result.getClass().getName();
    }

    private static boolean isTargetActivity(Activity activity) {
        try {
            return PACKAGE.equals(activity.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
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

    /**
     * 添加日志，并安排自动复制。
     */
    private static void diag(String message) {
        String line = "[VIVO_TRACE] " + message;

        Log.i(TAG, line);

        synchronized (DIAG_LOG) {
            DIAG_LOG.append(line).append('\n');

            if (DIAG_LOG.length() > MAX_LOG_LENGTH) {
                int deleteLength =
                        DIAG_LOG.length() - MAX_LOG_LENGTH / 2;

                DIAG_LOG.delete(0, deleteLength);
            }
        }

        scheduleClipboardCopy();
    }

    private static void diagError(
            String message,
            Throwable error
    ) {
        String detail;

        try {
            detail = Log.getStackTraceString(error);
        } catch (Throwable ignored) {
            detail = String.valueOf(error);
        }

        diag(message + "\n" + detail);
    }

    /**
     * 延迟复制，合并短时间内连续产生的日志。
     */
    private static void scheduleClipboardCopy() {
        MAIN_HANDLER.removeCallbacks(CLIPBOARD_COPY_RUNNABLE);
        MAIN_HANDLER.postDelayed(CLIPBOARD_COPY_RUNNABLE, 800);
    }

    private static final Runnable CLIPBOARD_COPY_RUNNABLE =
            () -> {
                Activity activity = currentActivity;

                if (activity == null) {
                    return;
                }

                try {
                    if (activity.isFinishing()
                            || activity.isDestroyed()) {
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
                            (ClipboardManager) activity
                                    .getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                    );

                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                        "Vivo 游戏中心诊断日志",
                                        snapshot
                                )
                        );
                    }

                } catch (Throwable error) {
                    Log.e(
                            TAG,
                            "复制诊断日志失败",
                            error
                    );
                }
            };
}

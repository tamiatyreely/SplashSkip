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

import io.github.libxposed.api.XposedModule;

/**
 * vivo 游戏中心去广告模块（功能版）。
 *
 * 目标包名：com.vivo.game
 *
 * 功能：
 * 1. 拦截 VivoADSDK 的初始化，从源头使广告 SDK 失效。
 * 2. 对于开屏页 LogoActivity，在检测到后尽快结束它并跳转主界面。
 * 3. 保留少量诊断日志，便于确认 Hook 生效。
 */
public final class VivoGameCenter {

    private static final String TAG = "VivoGameFilter";
    private static final String PACKAGE = "com.vivo.game";
    private static final String LOGO_ACTIVITY = "com.vivo.game.ui.LogoActivity";
    private static final String MAIN_ACTIVITY = "com.vivo.game.ui.GameTabActivity";
    private static final String AD_SDK = "com.vivo.adsdk.common.VivoADSDK";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final StringBuilder DIAG_LOG = new StringBuilder();
    private static final int MAX_LOG_LENGTH = 8000;
    private static final Set<String> HOOKED_METHODS = Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;
    private static volatile Activity currentActivity;

    private VivoGameCenter() {
    }

    public static synchronized void install(XposedModule module) {
        if (installed) return;
        installed = true;

        diag("vivo 游戏中心去广告模块启动 (功能版)");

        // 1. Hook VivoADSDK 的 init 方法，阻止广告 SDK 初始化
        hookAdSdkInit(module);

        // 2. Hook LogoActivity 的 onCreate，接管开屏页
        hookLogoActivity(module);

        // 保留生命周期记录，便于调试
        hookActivityLifecycle(module);
    }

    /**
     * 拦截 VivoADSDK 的所有 init 重载，使其不执行。
     * 这是去除弹窗广告的关键，因为 SDK 不初始化，后续广告加载/展示通常会失败或跳过。
     */
    private static void hookAdSdkInit(XposedModule module) {
        try {
            Class<?> sdkClass = Class.forName(AD_SDK, false, VivoGameCenter.class.getClassLoader());
            for (Method method : sdkClass.getDeclaredMethods()) {
                if ("init".equals(method.getName()) && !Modifier.isAbstract(method.getModifiers())) {
                    String sig = method.toGenericString();
                    if (!HOOKED_METHODS.add(sig)) continue;

                    module.hook(method).intercept(chain -> {
                        diag("拦截 VivoADSDK.init: " + sig);
                        // 不调用 chain.proceed()，直接返回 null (void方法)
                        // 对于非 void 返回值，这里假设 init 是 void。如果是 boolean，需返回 false。
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        } else if (method.getReturnType() == int.class) {
                            return 0;
                        }
                        return null;
                    });
                    diag("已阻止 VivoADSDK.init: " + sig);
                }
            }
        } catch (Throwable error) {
            diag("Hook VivoADSDK init 失败: " + error);
        }
    }

    /**
     * 接管 LogoActivity。
     * 在 onCreate 之后，延迟一小段时间（让基础初始化完成），然后 finish 自己并启动主界面。
     * 如果应用本身在 LogoActivity 中做了关键初始化，直接 finish 可能崩溃。
     * 如果测试发现崩溃，可以注释掉 finish 逻辑，仅依赖 SDK init 拦截。
     */
    private static void hookLogoActivity(XposedModule module) {
        try {
            Class<?> logoClass = Class.forName(LOGO_ACTIVITY, false, VivoGameCenter.class.getClassLoader());
            Method onCreate = logoClass.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed(); // 先让原始 onCreate 执行
                Object thisObj = chain.getThisObject();
                if (thisObj instanceof Activity) {
                    Activity activity = (Activity) thisObj;
                    currentActivity = activity;
                    diag("LogoActivity.onCreate，准备跳过开屏");
                    // 延迟 200ms 结束 LogoActivity 并启动主界面
                    MAIN_HANDLER.postDelayed(() -> {
                        try {
                            if (!activity.isFinishing()) {
                                Intent intent = new Intent();
                                intent.setClassName(PACKAGE, MAIN_ACTIVITY);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                activity.startActivity(intent);
                                activity.finish();
                                diag("已 finish LogoActivity 并启动主界面");
                            }
                        } catch (Throwable e) {
                            diag("跳过开屏失败: " + e);
                        }
                    }, 200);
                }
                return result;
            });
            diag("LogoActivity onCreate Hook 安装成功");
        } catch (Throwable error) {
            diag("Hook LogoActivity 失败: " + error);
        }
    }

    /**
     * 保留简单的生命周期记录，方便确认模块运行状态。
     */
    private static void hookActivityLifecycle(XposedModule module) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity act = (Activity) obj;
                    if (PACKAGE.equals(act.getPackageName())) {
                        currentActivity = act;
                        // 只记录非 Logo 的主要界面，减少日志
                        if (!LOGO_ACTIVITY.equals(act.getClass().getName())) {
                            diag("Activity: " + act.getClass().getSimpleName());
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void diag(String message) {
        String line = "[VIVO_FIX] " + message;
        Log.i(TAG, line);
        synchronized (DIAG_LOG) {
            DIAG_LOG.append(line).append('\n');
            if (DIAG_LOG.length() > MAX_LOG_LENGTH) {
                DIAG_LOG.delete(0, DIAG_LOG.length() - MAX_LOG_LENGTH / 2);
            }
        }
        // 调试期间可以保留剪贴板复制，稳定后可移除
        // scheduleClipboardCopy();
    }
}

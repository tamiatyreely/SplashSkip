package com.example.comiclsposed.apps;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * vivo 游戏中心专用逻辑。
 * 当前阶段：记录启动页/首页信息，自动复制诊断日志；只点击明确标记为“跳过”的开屏按钮。
 */
public final class VivoGameCenter {
    private static final String TAG = "VivoGameDiag";
    private static final String PACKAGE = "com.vivo.game";
    private static final String SPLASH_ACTIVITY = "com.vivo.game.ui.LogoActivity";
    private static final int MAX_LOG_CHARS = 16000;

    private static boolean installed;
    private static final StringBuilder LOGS = new StringBuilder();
    private static final Set<String> SNAPSHOTS = new LinkedHashSet<>();
    private static boolean skipClicked;
    private static boolean clipboardToastShown;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable CLIPBOARD_TASK = VivoGameCenter::copyToClipboardNow;

    private VivoGameCenter() {}

    public static synchronized void install(XposedModule module) {
        if (installed) return;
        installed = true;
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity a = (Activity) obj;
                    if (PACKAGE.equals(a.getPackageName())) {
                        currentActivity = a;
                        append("onCreate activity=" + a.getClass().getName()
                                + " taskId=" + a.getTaskId()
                                + " action=" + safeAction(a));
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            append("Hook onCreate 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity a = (Activity) obj;
                    if (PACKAGE.equals(a.getPackageName())) {
                        currentActivity = a;
                        append("onResume activity=" + a.getClass().getName()
                                + " version=" + appVersion(a));
                        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
                        if (decor != null) {
                            decor.postDelayed(() -> {
                                capture(a);
                                if (SPLASH_ACTIVITY.equals(a.getClass().getName())) {
                                    attemptSkip(a, 0);
                                }
                            }, 250);
                        }
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            append("Hook onResume 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        append("诊断模块已进入 vivo 游戏中心进程；日志会自动复制到剪贴板。");
    }

    private static String safeAction(Activity a) {
        try {
            return a.getIntent() == null || a.getIntent().getAction() == null
                    ? "(none)" : a.getIntent().getAction();
        } catch (Throwable ignored) {
            return "(unavailable)";
        }
    }

    private static String appVersion(Activity a) {
        try {
            PackageInfo p = a.getPackageManager().getPackageInfo(PACKAGE, 0);
            return p.versionName == null ? "unknown" : p.versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void attemptSkip(Activity a, int attempt) {
        try {
            if (a.isFinishing() || a.isDestroyed() || !SPLASH_ACTIVITY.equals(a.getClass().getName())) return;
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            View skip = findSkip(decor);
            if (skip != null && skip.isShown() && skip.isEnabled()) {
                boolean clicked = skip.performClick();
                append("开屏跳过按钮: id=skip, text=" + viewText(skip) + ", performClick=" + clicked);
                if (clicked) {
                    skipClicked = true;
                    return;
                }
            }
            if (attempt < 7) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> attemptSkip(a, attempt + 1), 400);
            } else {
                append("未找到可点击的明确“跳过”按钮；未关闭开屏页面。");
            }
        } catch (Throwable t) {
            append("尝试点击跳过按钮失败: " + t.getClass().getSimpleName());
        }
    }

    private static View findSkip(View root) {
        if (root == null) return null;
        try {
            int id = root.getId();
            if (id != View.NO_ID) {
                try {
                    String name = root.getResources().getResourceEntryName(id);
                    if ("skip".equalsIgnoreCase(name)) return root;
                } catch (Throwable ignored) {}
            }
            String text = viewText(root);
            if (root.isClickable() && text != null && text.contains("跳过")) return root;
            if (root.getContentDescription() != null
                    && root.isClickable()
                    && root.getContentDescription().toString().contains("跳过")) return root;
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findSkip(group.getChildAt(i));
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String viewText(View v) {
        if (v instanceof TextView && !(v instanceof EditText)) {
            CharSequence cs = ((TextView) v).getText();
            return cs == null ? "" : cs.toString().trim();
        }
        return "";
    }

    private static void capture(Activity a) {
        try {
            if (a.isFinishing() || a.isDestroyed()) return;
            View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (root == null) return;
            StringBuilder out = new StringBuilder();
            out.append("页面快照 activity=").append(a.getClass().getName());
            int[] count = {0};
            walk(root, out, count);
            String snapshot = out.toString();
            synchronized (SNAPSHOTS) {
                if (!SNAPSHOTS.add(snapshot)) return;
                while (SNAPSHOTS.size() > 8) {
                    String first = SNAPSHOTS.iterator().next();
                    SNAPSHOTS.remove(first);
                }
            }
            append(snapshot);
        } catch (Throwable t) {
            append("页面快照失败: " + t.getClass().getSimpleName());
        }
    }

    private static void walk(View v, StringBuilder out, int[] count) {
        if (v == null || count[0] >= 45) return;
        try {
            if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return;
            if (v instanceof TextView && !(v instanceof EditText)) {
                String text = viewText(v);
                if (!text.isEmpty()) {
                    out.append("\n  text(").append(v.getClass().getSimpleName()).append(")=")
                            .append(redact(limit(text, 80)));
                    count[0]++;
                }
            }
            CharSequence desc = v.getContentDescription();
            if (desc != null && desc.length() > 0 && count[0] < 45) {
                out.append("\n  desc(").append(v.getClass().getSimpleName()).append(")=")
                        .append(redact(limit(desc.toString(), 80)));
                count[0]++;
            }
            int id = v.getId();
            if (id != View.NO_ID && count[0] < 45) {
                try {
                    out.append("\n  id(").append(v.getClass().getSimpleName()).append(")=")
                            .append(v.getResources().getResourceEntryName(id));
                    count[0]++;
                } catch (Throwable ignored) {}
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && count[0] < 45; i++) {
                    walk(g.getChildAt(i), out, count);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String limit(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private static String redact(String s) {
        return s.replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "<phone>")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "<email>");
    }

    private static synchronized void append(String line) {
        String entry = "[VIVO_DIAG] " + redact(line);
        Log.i(TAG, entry);
        LOGS.append(entry).append('\n');
        if (LOGS.length() > MAX_LOG_CHARS) LOGS.delete(0, LOGS.length() - MAX_LOG_CHARS);
        copyToClipboard();
    }

    private static void copyToClipboard() {
        // 合并短时间内产生的多条日志，避免剪贴板只留下第一条或连续弹出 Toast。
        MAIN_HANDLER.removeCallbacks(CLIPBOARD_TASK);
        MAIN_HANDLER.postDelayed(CLIPBOARD_TASK, 450);
    }

    private static void copyToClipboardNow() {
        try {
            Activity a = currentActivity;
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            final String text;
            synchronized (VivoGameCenter.class) {
                text = LOGS.toString();
            }
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("VivoGameCenter diagnostics", text));
                if (!clipboardToastShown) {
                    clipboardToastShown = true;
                    Toast.makeText(a, "vivo 诊断日志已复制，可切回聊天粘贴", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "复制剪贴板失败: " + t.getClass().getSimpleName());
        }
    }

    private static volatile Activity currentActivity;
}

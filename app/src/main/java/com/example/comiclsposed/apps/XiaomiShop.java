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
import android.widget.PopupWindow;
import android.widget.TextView;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 小米商城 5.54.0.20260811.r1 诊断适配器。
 *
 * 本版只记录开屏/弹窗线索，不执行拦截、不模拟点击、不改变商城行为。
 * 重要事件发生时将最近的诊断日志自动复制到剪贴板。
 */
public final class XiaomiShop {
    private static final String TAG = "SplashSkip-Xiaomi";
    private static final String PACKAGE = "com.xiaomi.shop";
    private static final int MAX_LOG_CHARS = 50000;
    private static final int MAX_VIEW_NODES = 220;

    private static final StringBuilder LOG = new StringBuilder();
    private static final Set<String> HOOKED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<Activity> SEEN =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    private static volatile boolean installed;
    private static volatile boolean toastShown;
    private static volatile Activity lastActivity;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private XiaomiShop() {}

    public static synchronized void install(XposedModule module) {
        if (installed) return;
        installed = true;
        note("================================================");
        note("SplashSkip 小米商城诊断适配器启动");
        note("目标包名: " + PACKAGE);
        note("目标版本: 5.54.0.20260811.r1");
        note("模式: 只诊断，不拦截、不模拟点击");
        hookActivityLifecycle(module);
        hookDialogs(module);
        hookPopups(module);
        hookWebView(module);
        note("诊断 Hook 安装完成；打开商城并复现开屏/弹窗后，直接粘贴剪贴板日志。");
    }

    private static void hookActivityLifecycle(XposedModule module) {
        try {
            Method m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity a = (Activity) obj;
                    if (isTarget(a)) {
                        lastActivity = a;
                        note("Activity.onCreate: " + a.getClass().getName());
                        note("Intent action: " + safe(a.getIntent() == null ? null : a.getIntent().getAction()));
                        note("Intent data: " + safe(a.getIntent() == null || a.getIntent().getData() == null
                                ? null : a.getIntent().getData().toString()));
                        scheduleViewDump(a, "onCreate");
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onCreate");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                Object obj = chain.getThisObject();
                if (obj instanceof Activity) {
                    Activity a = (Activity) obj;
                    if (isTarget(a)) {
                        lastActivity = a;
                        if (SEEN.add(a)) note("Activity 首次 Resume: " + a.getClass().getName());
                        scheduleViewDump(a, "onResume");
                    }
                }
                return result;
            });
            note("Hook OK: Activity.onResume");
        } catch (Throwable t) {
            note("Hook FAIL Activity.onResume: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookDialogs(XposedModule module) {
        try {
            Method m = Dialog.class.getDeclaredMethod("show");
            module.hook(m).intercept(chain -> {
                Object obj = chain.getThisObject();
                if (obj instanceof Dialog) {
                    Dialog d = (Dialog) obj;
                    Context c = d.getContext();
                    if (isTarget(c)) {
                        note("Dialog.show: " + d.getClass().getName());
                        try {
                            if (d.getWindow() != null) {
                                note("Dialog Window type: " + d.getWindow().getClass().getName());
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                Object result = chain.proceed();
                if (obj instanceof Dialog && isTarget(((Dialog) obj).getContext())) {
                    final Dialog d = (Dialog) obj;
                    MAIN.postDelayed(() -> {
                        try {
                            if (d.isShowing() && d.getWindow() != null) {
                                note("Dialog 已显示: " + d.getClass().getName());
                                View v = d.getWindow().getDecorView();
                                dumpViewTree(v, "DialogView");
                                copyClipboard(d.getContext());
                            }
                        } catch (Throwable t) {
                            note("Dialog 读取失败: " + t.getClass().getSimpleName());
                        }
                    }, 250);
                }
                return result;
            });
            note("Hook OK: Dialog.show");
        } catch (Throwable t) {
            note("Hook FAIL Dialog.show: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void hookPopups(XposedModule module) {
        int count = 0;
        for (Method m : PopupWindow.class.getDeclaredMethods()) {
            String n = m.getName();
            if (!n.startsWith("show")) continue;
            if (m.getParameterTypes().length == 0) continue;
            String key = m.toGenericString();
            if (!HOOKED.add(key)) continue;
            try {
                module.hook(m).intercept(chain -> {
                    Object obj = chain.getThisObject();
                    if (obj instanceof PopupWindow) {
                        PopupWindow p = (PopupWindow) obj;
                        View content = p.getContentView();
                        Context c = content == null ? null : content.getContext();
                        if (isTarget(c)) {
                            note("PopupWindow." + n + ": " + p.getClass().getName()
                                    + ", content=" + (content == null ? "null" : content.getClass().getName()));
                        }
                    }
                    Object result = chain.proceed();
                    if (obj instanceof PopupWindow) {
                        PopupWindow p = (PopupWindow) obj;
                        View content = p.getContentView();
                        Context c = content == null ? null : content.getContext();
                        if (isTarget(c)) {
                            dumpViewTree(content, "PopupView");
                            copyClipboard(c);
                        }
                    }
                    return result;
                });
                count++;
            } catch (Throwable t) {
                note("Hook FAIL PopupWindow." + n + ": " + t.getClass().getSimpleName());
            }
        }
        note("PopupWindow show* Hook 数量: " + count);
    }

    private static void hookWebView(XposedModule module) {
        try {
            Method m = WebView.class.getDeclaredMethod("loadUrl", String.class);
            module.hook(m).intercept(chain -> {
                Object obj = chain.getThisObject();
                Object url = chain.getArg(0);
                if (obj instanceof WebView && isTarget(((WebView) obj).getContext())) {
                    note("WebView.loadUrl: " + scrubUrl(String.valueOf(url)));
                }
                return chain.proceed();
            });
            note("Hook OK: WebView.loadUrl(String)");
        } catch (Throwable t) {
            note("Hook FAIL WebView.loadUrl: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isTarget(Activity a) {
        try { return a != null && PACKAGE.equals(a.getPackageName()); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean isTarget(Context c) {
        try { return c != null && PACKAGE.equals(c.getPackageName()); }
        catch (Throwable ignored) { return false; }
    }

    private static void scheduleViewDump(final Activity a, final String source) {
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(() -> {
                try {
                    note("---- View 树快照 (" + source + "): " + a.getClass().getName() + " ----");
                    dumpViewTree(a.getWindow().getDecorView(), "ActivityView");
                    copyClipboard(a);
                } catch (Throwable t) {
                    note("View 树读取失败: " + t.getClass().getSimpleName());
                }
            }, 700);
        } catch (Throwable ignored) {}
    }

    private static synchronized void note(String s) {
        String line = timestamp() + " " + s;
        Log.i(TAG, line);
        LOG.append(line).append('\n');
        if (LOG.length() > MAX_LOG_CHARS) {
            LOG.delete(0, LOG.length() - MAX_LOG_CHARS);
        }
    }

    private static void dumpViewTree(View root, String prefix) {
        if (root == null) {
            note(prefix + ": root=null");
            return;
        }
        int[] count = {0};
        dumpNode(root, prefix, 0, count);
        if (count[0] >= MAX_VIEW_NODES) note(prefix + ": 达到节点上限 " + MAX_VIEW_NODES);
    }

    private static void dumpNode(View v, String prefix, int depth, int[] count) {
        if (v == null || count[0] >= MAX_VIEW_NODES || depth > 18) return;
        count[0]++;
        StringBuilder line = new StringBuilder();
        line.append(prefix).append(" ").append(indent(depth))
                .append(v.getClass().getName());
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                line.append(" id=").append(id);
                try { line.append("(").append(v.getResources().getResourceEntryName(id)).append(")"); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            CharSequence desc = v.getContentDescription();
            if (desc != null && desc.length() > 0) line.append(" desc=\"").append(trim(desc.toString(), 100)).append("\"");
        } catch (Throwable ignored) {}
        if (v instanceof TextView) {
            try {
                CharSequence text = ((TextView) v).getText();
                if (text != null && text.length() > 0) {
                    String txt = trim(text.toString().replace('\n', ' '), 120);
                    line.append(" text=\"").append(txt).append("\"");
                }
            } catch (Throwable ignored) {}
        }
        line.append(" visibility=").append(v.getVisibility())
                .append(" size=").append(v.getWidth()).append("x").append(v.getHeight());
        note(line.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int children = g.getChildCount();
            for (int i = 0; i < children && count[0] < MAX_VIEW_NODES; i++) {
                dumpNode(g.getChildAt(i), prefix, depth + 1, count);
            }
        }
    }

    private static String indent(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n && i < 20; i++) b.append("  ");
        return b.toString();
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new java.util.Date());
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("(?i)(token|cookie|session|auth|password|phone|mobile)\\s*[:=]\\s*[^,; ]+", "$1=<hidden>");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String scrubUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        if (q >= 0) return url.substring(0, q) + "?<query-hidden>";
        return url;
    }

    private static void copyClipboard(Context context) {
        if (context == null || !isTarget(context)) return;
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            String contents;
            synchronized (XiaomiShop.class) {
                contents = LOG.toString();
            }
            cm.setPrimaryClip(ClipData.newPlainText("SplashSkip 小米商城诊断日志", contents));
            if (!toastShown && context instanceof Activity) {
                toastShown = true;
                android.widget.Toast.makeText(context, "SplashSkip：诊断日志已复制到剪贴板", android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "复制剪贴板失败: " + t.getClass().getSimpleName());
        }
    }
}


package io.github.ferstar.wpsroottoast;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.error.HookFailedError;

/**
 * WPS Root 提醒过滤
 * 只过滤指定的 Root 风险 Toast，不修改 Root 检测。
 */
public final class Hook extends XposedModule {

    private static final String TARGET_PACKAGE = "cn.wps.moffice_eng";
    private static final String TAG = "WpsRootToast";

    private boolean installed;

    private final WarningToastRegistry<Toast> warnings =
            new WarningToastRegistry<>();

    @Override
    public void onPackageReady(PackageReadyParam app) {
        if (TARGET_PACKAGE.equals(app.getPackageName())) {
            installFilter();
        }
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        String process = param.getProcessName();

        if (process != null &&
                (TARGET_PACKAGE.equals(process)
                        || process.startsWith(TARGET_PACKAGE + ":"))) {
            installFilter();
        }
    }

    private synchronized void installFilter() {
        if (installed) return;

        List<HookHandle> handles = new ArrayList<>();

        try {
            // 监听普通文本 Toast
            handles.add(
                    hook(Toast.class.getDeclaredMethod(
                            "makeText",
                            Context.class,
                            CharSequence.class,
                            int.class
                    ))
                    .setId("wps-warning-create-text")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Toast result = (Toast) chain.proceed();

                        warnings.record(
                                result,
                                (CharSequence) chain.getArgs().get(1)
                        );

                        return result;
                    })
            );

            // 监听资源 ID Toast
            handles.add(
                    hook(Toast.class.getDeclaredMethod(
                            "makeText",
                            Context.class,
                            int.class,
                            int.class
                    ))
                    .setId("wps-warning-create-resource")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Toast result = (Toast) chain.proceed();

                        Context context =
                                (Context) chain.getArgs().get(0);

                        int resourceId =
                                (Integer) chain.getArgs().get(1);

                        warnings.record(
                                result,
                                context.getText(resourceId)
                        );

                        return result;
                    })
            );

            // 监听 Toast 文本修改
            handles.add(
                    hook(Toast.class.getDeclaredMethod(
                            "setText",
                            CharSequence.class
                    ))
                    .setId("wps-warning-change-text")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();

                        warnings.record(
                                (Toast) chain.getThisObject(),
                                (CharSequence) chain.getArgs().get(0)
                        );

                        return result;
                    })
            );

            // 处理 setText(int) 的内联情况
            deoptimize(
                    Toast.class.getDeclaredMethod(
                            "setText",
                            int.class
                    )
            );

            // 自定义 View 的 Toast 不属于目标文本提示
            handles.add(
                    hook(Toast.class.getDeclaredMethod(
                            "setView",
                            View.class
                    ))
                    .setId("wps-warning-custom-view")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();

                        warnings.clear(
                                (Toast) chain.getThisObject()
                        );

                        return result;
                    })
            );

            // 只拦截匹配的 Root 提醒
            handles.add(
                    hook(Toast.class.getDeclaredMethod("show"))
                    .setId("wps-warning-show")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (warnings.shouldBlock(
                                (Toast) chain.getThisObject()
                        )) {
                            log(
                                    Log.INFO,
                                    TAG,
                                    "Blocked matching WPS Root warning"
                            );

                            return null;
                        }

                        return chain.proceed();
                    })
            );

            installed = true;

            log(
                    Log.INFO,
                    TAG,
                    "WPS Root warning filter installed"
            );

        } catch (
                ReflectiveOperationException |
                RuntimeException |
                LinkageError |
                HookFailedError ignored
        ) {
            for (HookHandle handle : handles) {
                handle.unhook();
            }

            log(
                    Log.WARN,
                    TAG,
                    "Filter unavailable; WPS behavior unchanged"
            );
        }
    }
}

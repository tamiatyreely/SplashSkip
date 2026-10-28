package com.example.comiclsposed;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * vivo Game Center adapter.
 *
 * This first merged version is diagnostic-only. It records Activity lifecycle
 * events so the installed app's actual startup flow can be identified before
 * adding a version-specific skip rule.
 */
public final class VivoGameModule extends XposedModule {
    private static final String TAG = "MultiSplashSkip/Vivo";
    // Verify this package ID on the target phone. It can differ by firmware/region.
    private static final String TARGET_PACKAGE = "com.vivo.game";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded; process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            String pkg = param.getPackageName();
            if (!TARGET_PACKAGE.equals(pkg)) {
                return;
            }

            log(Log.INFO, TAG, "Vivo adapter active for " + pkg);
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity activity = (Activity) self;
                        log(Log.INFO, TAG, "ACTIVITY_CREATE class="
                                + activity.getClass().getName());
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Activity create log failed: " + t);
                }
                return result;
            });

            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity activity = (Activity) self;
                        log(Log.INFO, TAG, "ACTIVITY_RESUME class="
                                + activity.getClass().getName());
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Activity resume log failed: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to initialize vivo adapter", t);
        }
    }
}

package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;
import android.os.LocaleList;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Hook for ILocaleManager (Android 13+ / API 33+)
 * Fixes SecurityException: setApplicationLocales: Neither user nor current process has android.permission.CHANGE_CONFIGURATION
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService("locale");
            if (binder == null) return null;
            Class<?> stubClass = Class.forName("android.app.ILocaleManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Exception e) {
            Slog.d(TAG, "getWho error: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRServiceManager.get().getService("locale") != null) {
            replaceSystemService("locale");
            Slog.d(TAG, "Hooked LocaleManagerService");
        } else {
            Slog.d(TAG, "Skipping LocaleManagerService hook (service not found)");
        }
    }

    @Override
    public boolean isBadEnv() {
        IBinder binder = BRServiceManager.get().getService("locale");
        return binder != null && binder != this;
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                // setApplicationLocales(String packageName, int userId, LocaleList locales, boolean fromDelegate)
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof String) {
                        String pkg = (String) args[i];
                        if (pkg != null && !pkg.equals(BlackBoxCore.getHostPkg())) {
                            Slog.d(TAG, "Fixing package name in setApplicationLocales: " + pkg + " -> " + BlackBoxCore.getHostPkg());
                            args[i] = BlackBoxCore.getHostPkg();
                        }
                    }
                }
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "Swallowed exception in setApplicationLocales: " + t.getMessage());
                return null;
            }
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof String) {
                        String pkg = (String) args[i];
                        if (pkg != null && !pkg.equals(BlackBoxCore.getHostPkg())) {
                            args[i] = BlackBoxCore.getHostPkg();
                        }
                    }
                }
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable t) {
                return LocaleList.getEmptyLocaleList();
            }
        }
    }
}

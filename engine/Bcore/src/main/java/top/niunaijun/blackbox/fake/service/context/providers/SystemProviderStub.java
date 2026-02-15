package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * updated by alex5402 on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 
 */
public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }

        String methodName = method.getName();

        // Safe replacement for "call" method
        if ("call".equals(methodName)) {
            if (args != null && args.length > 0) {
                Object arg0 = args[0];
                if (arg0 instanceof String) {
                    String strArg0 = (String) arg0;
                    String hostPkg = BlackBoxCore.getHostPkg();
                    top.niunaijun.blackbox.entity.AppConfig config = top.niunaijun.blackbox.app.BActivityThread
                            .getAppConfig();
                    String guestPkg = config != null ? config.packageName : null;

                    // Log for debugging
                    top.niunaijun.blackbox.utils.Slog.d("SystemProviderStub", "call method: " + methodName);
                    if (config == null) {
                        top.niunaijun.blackbox.utils.Slog.d("SystemProviderStub", "AppConfig is null!");
                    } else {
                        top.niunaijun.blackbox.utils.Slog.d("SystemProviderStub",
                                "Guest Pkg: " + guestPkg + ", arg0: " + strArg0);
                    }

                    // Only replace if it matches the Guest Package
                    if (guestPkg != null && strArg0.equals(guestPkg)) {
                        top.niunaijun.blackbox.utils.Slog.d("SystemProviderStub",
                                "Replacing " + strArg0 + " with " + hostPkg);
                        args[0] = hostPkg;
                    } else {
                        // Fallback: If arg0 looks like a package (contains dot) and NOT like a method
                        // (no GET_), replace it?
                        // Dangerous for Fire TV.
                        // But let's log if we MISS a replacement
                        if (strArg0.contains(".") && !strArg0.startsWith("GET_") && !strArg0.startsWith("PUT_")) {
                            top.niunaijun.blackbox.utils.Slog.d("SystemProviderStub", "POTENTIAL MISS: arg0 " + strArg0
                                    + " looks like package but didn't match guestPkg " + guestPkg);
                        }
                    }
                }

                Class<?> attributionSourceClass = BRAttributionSource.getRealClass();
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg != null && attributionSourceClass != null &&
                            arg.getClass().getName().equals(attributionSourceClass.getName())) {
                        ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                    }
                }
            }
            return method.invoke(mBase, args);
        }

        // For other methods like query/insert/update/delete, we may need to fix package
        // names
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                String authority = (String) arg;
                if (!isSystemProviderAuthority(authority)) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
            } else if (arg != null) {
                Class<?> attrSourceClass = BRAttributionSource.getRealClass();
                if (attrSourceClass != null && arg.getClass().getName().equals(attrSourceClass.getName())) {
                    ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                }
            }
        }
        return method.invoke(mBase, args);
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null)
            return false;
        // Common system provider authorities that should not be replaced
        return authority.equals("settings") ||
                authority.equals("media") ||
                authority.equals("downloads") ||
                authority.equals("contacts") ||
                authority.equals("call_log") ||
                authority.equals("telephony") ||
                authority.equals("calendar") ||
                authority.equals("browser") ||
                authority.equals("user_dictionary") ||
                authority.equals("applications") ||
                authority.startsWith("com.android.") ||
                authority.startsWith("android.");
    }
}

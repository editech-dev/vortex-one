package top.niunaijun.blackbox.fake.service.libcore;

import android.os.Process;

import java.lang.reflect.Method;

import black.libcore.io.BRLibcore;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * updated by alex5402 on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 
 */
public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = IOCore.get().redirectPath(orig);
//                    if (!ObjectsCompat.equals(orig, args[i])) {
//                        Log.d(TAG, "redirectPath: " + orig + "  => " + args[i]);
//                    }
                }
            }
        }
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("stat")
    public static class stat extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object invoke = null;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
            Reflector.with(invoke).field("st_uid").set(getFakeUid(-1));
            return invoke;
        }
    }

    @ProxyMethod("connect")
    public static class connect extends MethodHook {
        
        // Recursion guard to prevent infinite loops if firewall logic triggers network calls
        private static final ThreadLocal<Boolean> sIsChecking = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return false;
            }
        };

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (sIsChecking.get()) {
                return method.invoke(who, args);
            }

            try {
                sIsChecking.set(true);
                
                // args: FileDescriptor fd, InetAddress address, int port
                if (args != null && args.length >= 3) {
                    if (args[1] instanceof java.net.InetAddress && args[2] instanceof Integer) {
                        java.net.InetAddress address = (java.net.InetAddress) args[1];
                        int port = (Integer) args[2];
                        
                        // Call Firewall Monitor using reflection to avoid compilation issues with Kotlin
                        try {
                            Class<?> monitorClass = Class.forName("com.editech.services.firewall.NetworkConnectionMonitor");
                            // Kotlin object methods with @JvmStatic are static in Java
                            Method checkMethod = monitorClass.getMethod("checkAndThrowIfBlocked", java.net.InetAddress.class, int.class);
                            checkMethod.invoke(null, address, port);
                        } catch (Exception e) {
                            if (e.getCause() instanceof java.net.SocketException) {
                                throw e.getCause();
                            }
                            // Ignore other reflection errors
                        }
                    }
                }
            } catch (Throwable e) {
                if (e instanceof java.net.SocketException) {
                    throw e;
                }
                // top.niunaijun.blackbox.utils.Slog.e(TAG, "Firewall check failed: " + e.getMessage());
            } finally {
                sIsChecking.remove(); // Use remove() instead of set(false) to prevent memory leaks
            }
            
            try {
                return method.invoke(who, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }

    private static int getFakeUid(int callUid) {
        if (callUid > 0 && callUid <= Process.FIRST_APPLICATION_UID)
            return callUid;
//            Log.d(TAG, "getuid: " + BActivityThread.getAppPackageName() + ", " + BActivityThread.getAppUid());
        if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
            return BActivityThread.getBAppId();
        } else {
            return BlackBoxCore.getHostUid();
        }
    }
}

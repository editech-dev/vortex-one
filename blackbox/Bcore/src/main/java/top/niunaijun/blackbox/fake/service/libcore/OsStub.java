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

            java.net.InetAddress address = null;
            int port = 0;
            boolean shouldBlock = false;
            Method logMethod = null;
            Method checkMethod = null;

            try {
                // Parse args
                if (args != null && args.length >= 3) {
                    if (args[1] instanceof java.net.InetAddress && args[2] instanceof Integer) {
                        address = (java.net.InetAddress) args[1];
                        port = (Integer) args[2];
                    }
                }

                if (address != null) {
                    sIsChecking.set(true);
                    
                    // 1. Check if we should block (Reflection)
                    try {
                        Class<?> monitorClass = Class.forName("com.editech.services.firewall.NetworkConnectionMonitor");
                        checkMethod = monitorClass.getMethod("shouldBlockSocket", java.net.InetAddress.class, int.class);
                        logMethod = monitorClass.getMethod("logSocketConnection", java.net.InetAddress.class, int.class, boolean.class, String.class);
                        
                        shouldBlock = (boolean) checkMethod.invoke(null, address, port);
                    } catch (Exception e) {
                        // Reflection failed, safely proceed
                    }
                    
                    // 2. If blocked, Log and Throw
                    if (shouldBlock) {
                        if (logMethod != null) {
                            try {
                                logMethod.invoke(null, address, port, true, "BLOCKED");
                            } catch (Exception e) {}
                        }
                        throw new java.net.SocketException("Connection blocked by firewall");
                    }
                }
            } catch (Throwable e) {
                 if (e instanceof java.net.SocketException) {
                    throw e;
                }
            } finally {
                sIsChecking.remove();
            }
            
            // 3. Attempt to connect (Original Method)
            Object result;
            try {
                result = method.invoke(who, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Connection Failed at OS level
                 if (address != null && logMethod != null) {
                    try {
                        // We need to re-set recursion guard briefly to log? 
                        // Actually NetworkConnectionMonitor handles its own logic, safe to call.
                        // But wait, logSocketConnection might trigger something? no it just DB writes.
                        
                        // Error message? e.getTargetException().getMessage()
                        logMethod.invoke(null, address, port, false, "FAILED"); 
                    } catch (Exception ex) {}
                }
                throw e.getTargetException();
            }

            // 4. Connection Success
            if (address != null && logMethod != null) {
                 try {
                    logMethod.invoke(null, address, port, false, "ESTABLISHED");
                } catch (Exception e) {}
            }

            return result;
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

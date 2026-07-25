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

        // Cached Tor reflection refs — resolved once, reused on every connection
        private static volatile java.lang.reflect.Method sTorEnabledMethod;
        private static volatile java.lang.reflect.Method sTorProxyMethod;
        private static volatile boolean sTorReflectionFailed = false;

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
                        // Updated signature: logSocketConnection(InetAddress, int, boolean, String, String)
                        logMethod = monitorClass.getMethod("logSocketConnection", java.net.InetAddress.class, int.class, boolean.class, String.class, String.class);

                        shouldBlock = (boolean) checkMethod.invoke(null, address, port);
                    } catch (Exception e) {
                        // Reflection failed, safely proceed
                    }

                    // 2. If blocked, Log and Throw
                    if (shouldBlock) {
                        if (logMethod != null) {
                            try {
                                logMethod.invoke(null, address, port, true, "BLOCKED", "Firewall Rule");
                            } catch (Exception e) {}
                        }
                        throw new java.net.SocketException("Connection blocked by firewall");
                    }

                    // ── TOR REDIRECT ──────────────────────────────────────────────────────────
                    // Check if this virtual app has Tor routing enabled.
                    // If yes, tunnel through 127.0.0.1:9150 (SOCKS5).
                    // If Tor is enabled but proxy is down -> BLOCK (kill-switch).
                    if (!sTorReflectionFailed) {
                        try {
                            ensureTorReflection();
                            if (sTorEnabledMethod != null) {
                                String pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
                                if (pkg != null) {
                                    boolean torEnabled = (boolean) sTorEnabledMethod.invoke(null, pkg);
                                    if (torEnabled) {
                                        boolean proxyUp = (boolean) sTorProxyMethod.invoke(null);
                                        if (!proxyUp) {
                                            // Kill-switch: log as TOR/BLOCKED and refuse connection
                                            logTorConnection(address.getHostAddress(), port,
                                                    true, "BLOCKED", "Tor proxy not ready",
                                                    "TOR/BLOCKED", pkg);
                                            throw new java.net.SocketException(
                                                    "[Tor] Proxy not ready — connection blocked for safety");
                                        }
                                        // Proxy is up — tunnel through SOCKS5
                                        sIsChecking.remove();
                                        return connectViaTorSocks5(who, method, args, address, port, pkg);
                                    }
                                }
                            }
                        } catch (java.net.SocketException se) {
                            throw se; // Re-throw kill-switch blocks
                        } catch (Exception ignored) {}
                    }
                    // ── END TOR REDIRECT ──────────────────────────────────────────────────────
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
                        Throwable cause = e.getTargetException();
                        String reason = cause != null ? cause.getMessage() : "Unknown Error";
                        if (reason == null) reason = cause.getClass().getSimpleName();

                        logMethod.invoke(null, address, port, false, "FAILED", reason);
                    } catch (Exception ex) {}
                }
                throw e.getTargetException();
            }

            // 4. Connection Success
            if (address != null && logMethod != null) {
                try {
                    logMethod.invoke(null, address, port, false, "ESTABLISHED", null);
                } catch (Exception e) {}
            }

            return result;
        }

        // ── Tor helpers ───────────────────────────────────────────────────────────

        private static void ensureTorReflection() {
            if (sTorEnabledMethod != null || sTorReflectionFailed) return;
            try {
                Class<?> torMgr = Class.forName("com.editech.services.tor.TorManager");
                sTorEnabledMethod = torMgr.getMethod("isTorEnabledForPackage", String.class);
                sTorProxyMethod   = torMgr.getMethod("isProxyReachable");
            } catch (Exception e) {
                sTorReflectionFailed = true;
            }
        }

        /**
         * Tunnels the connection through the local Tor SOCKS5 proxy (127.0.0.1:9150).
         *
         * Protocol (RFC 1928):
         *  1. Connect the OS-level fd to 127.0.0.1:9150
         *  2. ClientHello   -> [0x05, 0x01, 0x00]           (SOCKS5, NO_AUTH)
         *  3. ServerHello   <- [0x05, 0x00]                  (accepted)
         *  4. CONNECT req   -> [0x05, 0x01, 0x00, atyp, addr, port]
         *  5. CONNECT resp  <- [0x05, 0x00, 0x00, ...]       (success)
         *
         * The FileDescriptor in args[0] is now transparently connected to
         * the target host via the Tor exit node.
         */
        private static Object connectViaTorSocks5(
                Object who, Method method, Object[] args,
                java.net.InetAddress targetAddr, int targetPort,
                String pkg) throws Throwable {

            java.net.InetAddress proxyAddr =
                    java.net.InetAddress.getByName("127.0.0.1");
            int proxyPort = 9150;

            // Step 1: Connect the underlying fd to the SOCKS5 proxy
            Object[] proxyArgs = args.clone();
            proxyArgs[1] = proxyAddr;
            proxyArgs[2] = proxyPort;
            method.invoke(who, proxyArgs);

            // Step 2: Obtain streams from the FileDescriptor
            java.io.FileDescriptor fd = (java.io.FileDescriptor) args[0];
            java.io.FileOutputStream fos = new java.io.FileOutputStream(fd);
            java.io.FileInputStream  fis = new java.io.FileInputStream(fd);

            try {
                // Step 3: SOCKS5 greeting
                fos.write(new byte[]{0x05, 0x01, 0x00}); // VER=5, NMETHODS=1, NO_AUTH
                fos.flush();
                byte[] greeting = readExact(fis, 2);
                if (greeting[0] != 0x05 || greeting[1] != 0x00) {
                    throw new java.net.SocketException(
                            "[Tor] SOCKS5 auth negotiation failed: 0x"
                                    + Integer.toHexString(greeting[1] & 0xFF));
                }

                // Step 4: CONNECT request
                byte[] ip = targetAddr.getAddress();
                byte[] req;
                if (ip.length == 4) {
                    // IPv4 - ATYP=0x01
                    req = new byte[]{
                            0x05, 0x01, 0x00, 0x01,
                            ip[0], ip[1], ip[2], ip[3],
                            (byte) (targetPort >> 8), (byte) (targetPort & 0xFF)
                    };
                } else {
                    // IPv6 - ATYP=0x04
                    req = new byte[22];
                    req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x04;
                    System.arraycopy(ip, 0, req, 4, 16);
                    req[20] = (byte) (targetPort >> 8);
                    req[21] = (byte) (targetPort & 0xFF);
                }
                fos.write(req);
                fos.flush();

                // Step 5: Read response
                byte[] rep = readExact(fis, 4);
                if (rep[0] != 0x05 || rep[1] != 0x00) {
                    String socks5Error = socks5ErrorMessage(rep[1] & 0xFF);
                    logTorConnection(targetAddr.getHostAddress(), targetPort,
                            false, "FAILED", "SOCKS5: " + socks5Error,
                            "TOR/FAILED", pkg);
                    throw new java.net.SocketException("[Tor] CONNECT rejected: " + socks5Error);
                }
                // Consume remaining response bytes (bound address + port)
                int atyp = rep[3] & 0xFF;
                if      (atyp == 0x01) readExact(fis, 6);  // IPv4 (4 bytes) + port (2 bytes)
                else if (atyp == 0x03) { int len = fis.read(); readExact(fis, len + 2); } // domain
                else if (atyp == 0x04) readExact(fis, 18); // IPv6 (16 bytes) + port (2 bytes)

                // Success
                logTorConnection(targetAddr.getHostAddress(), targetPort,
                        false, "ESTABLISHED", null, "TOR/TCP", pkg);
                return null;

            } catch (java.net.SocketException se) {
                throw se;
            } catch (Exception e) {
                logTorConnection(targetAddr.getHostAddress(), targetPort,
                        false, "FAILED", e.getMessage(), "TOR/FAILED", pkg);
                throw new java.net.SocketException("[Tor] SOCKS5 error: " + e.getMessage());
            }
        }

        /** Reads exactly {@code count} bytes from the stream. */
        private static byte[] readExact(java.io.InputStream is, int count) throws java.io.IOException {
            byte[] buf = new byte[count];
            int read = 0;
            while (read < count) {
                int n = is.read(buf, read, count - read);
                if (n < 0) throw new java.io.EOFException("Stream ended prematurely");
                read += n;
            }
            return buf;
        }

        /** Human-readable SOCKS5 REP error code. */
        private static String socks5ErrorMessage(int code) {
            switch (code) {
                case 0x01: return "General failure";
                case 0x02: return "Connection not allowed by ruleset";
                case 0x03: return "Network unreachable";
                case 0x04: return "Host unreachable";
                case 0x05: return "Connection refused";
                case 0x06: return "TTL expired";
                case 0x07: return "Command not supported";
                case 0x08: return "Address type not supported";
                default:   return "Unknown (0x" + Integer.toHexString(code) + ")";
            }
        }

        /**
         * Logs a Tor connection attempt into FirewallManager via reflection.
         * Uses the protocol tag ("TOR/TCP", "TOR/BLOCKED", "TOR/FAILED") so that
         * the Logs tab can display the onion badge and purple color.
         */
        private static void logTorConnection(
                String ip, int port, boolean blocked,
                String status, String reason,
                String protocol, String pkg) {
            try {
                Class<?> ncClass = Class.forName(
                        "com.editech.services.firewall.NetworkConnectionMonitor");
                java.lang.reflect.Method m = ncClass.getMethod("logTorConnection",
                        String.class, int.class, boolean.class,
                        String.class, String.class, String.class);
                m.invoke(null, ip, port, blocked, status, reason, protocol);
            } catch (Exception ignored) {}
        }
    }

    // ============================
    // BANDWIDTH THROTTLING HOOKS
    // ============================

    // Cached reflection references for BandwidthManager (avoid lookup on every call)
    private static volatile Method sConsumeTxMethod;
    private static volatile Method sConsumeRxMethod;
    private static volatile boolean sBandwidthReflectionFailed = false;

    /**
     * Cache the last known package name per-thread so that IO/async threads
     * (OkHttp dispatcher, ExoPlayer loader, etc.) that run AFTER the app is
     * initialized can still be throttled even if BActivityThread.isInit()
     * returns false on that particular thread.
     */
    private static final ThreadLocal<String> sThreadPkgCache = new ThreadLocal<>();

    private static void ensureBandwidthReflection() {
        if (sBandwidthReflectionFailed || sConsumeTxMethod != null) return;
        try {
            Class<?> bwClass = Class.forName("com.editech.services.firewall.BandwidthManager");
            sConsumeTxMethod = bwClass.getMethod("consumeTx", String.class, int.class);
            sConsumeRxMethod = bwClass.getMethod("consumeRx", String.class, int.class);
        } catch (Exception e) {
            sBandwidthReflectionFailed = true;
        }
    }

    /**
     * Returns the package name of the currently running virtual app.
     * Tries BActivityThread first (reliable on the main/binder thread).
     * Falls back to a per-thread cache populated on previous successful calls
     * so that IO/background threads are also throttled.
     */
    private static String getCurrentPackageName() {
        try {
            if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
                String pkg = BActivityThread.getAppPackageName();
                if (pkg != null) {
                    // Populate cache for other threads that share this app context
                    sThreadPkgCache.set(pkg);
                    return pkg;
                }
            }
        } catch (Exception ignored) {}
        // Fallback: return whatever the last successful lookup stored for this thread
        return sThreadPkgCache.get();
    }

    @ProxyMethod("sendto")
    public static class sendto extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // sendto(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount, int flags, InetAddress inetAddress, int port)
            // or sendto(FileDescriptor fd, ByteBuffer buffer, int flags, InetAddress inetAddress, int port)
            Object result = method.invoke(who, args);
            
            // Throttle after send succeeds
            int bytesSent = 0;
            if (result instanceof Integer) {
                bytesSent = (int) result;
            } else if (args.length >= 4 && args[3] instanceof Integer) {
                // byteCount argument
                bytesSent = (int) args[3];
            }

            if (bytesSent > 0) {
                ensureBandwidthReflection();
                if (sConsumeTxMethod != null) {
                    try {
                        String pkg = getCurrentPackageName();
                        if (pkg != null) {
                            long delayMs = (long) sConsumeTxMethod.invoke(null, pkg, bytesSent);
                            if (delayMs > 0) {
                                Thread.sleep(delayMs);
                            }
                        }
                        // If pkg is still null here the thread cache has no entry yet;
                        // this can happen on the very first call before any init thread
                        // has run. In that case we skip throttling (no limit registered yet).
                    } catch (Exception ignored) {}
                }
            }

            return result;
        }
    }

    @ProxyMethod("recvfrom")
    public static class recvfrom extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // recvfrom(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount, int flags, InetSocketAddress srcAddress)
            Object result = method.invoke(who, args);

            // Throttle after receive completes
            int bytesReceived = 0;
            if (result instanceof Integer) {
                bytesReceived = (int) result;
            }

            if (bytesReceived > 0) {
                ensureBandwidthReflection();
                if (sConsumeRxMethod != null) {
                    try {
                        String pkg = getCurrentPackageName();
                        if (pkg != null) {
                            long delayMs = (long) sConsumeRxMethod.invoke(null, pkg, bytesReceived);
                            if (delayMs > 0) {
                                Thread.sleep(delayMs);
                            }
                        }
                        // If pkg is still null here the thread cache has no entry yet;
                        // this can happen on the very first call before any init thread
                        // has run. In that case we skip throttling (no limit registered yet).
                    } catch (Exception ignored) {}
                }
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

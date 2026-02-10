package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Hooks java.net.URL to capture request URLs.
 * Injects a globally shared URLStreamHandlerFactory.
 */
public class NetworkHook {
    private static final String TAG = "NetworkHook";
    private static boolean sInited = false;

    private static final Map<String, URLStreamHandler> sDefaultHandlers = new HashMap<>();

    public static void inject() {
        if (sInited) return;
        try {
            // 1. Capture default handlers BEFORE setting the factory
            saveDefaultHandler("http");
            saveDefaultHandler("https");
            
            // 2. Set the factory
            URL.setURLStreamHandlerFactory(new LoggingURLStreamHandlerFactory());
            sInited = true;
            Slog.d(TAG, "NetworkHook URLStreamHandlerFactory injected");
        } catch (Throwable e) {
            Slog.e(TAG, "Failed to inject URLStreamHandlerFactory: " + e.getMessage());
        }
    }

    private static void saveDefaultHandler(String protocol) {
        try {
            URL url = new URL(protocol, "example.com", 80, "/");
            // Field "handler" is transient in some versions, but usually present.
            // It might be named differently or accessible via reflection.
            java.lang.reflect.Field handlerField = URL.class.getDeclaredField("handler");
            handlerField.setAccessible(true);
            URLStreamHandler handler = (URLStreamHandler) handlerField.get(url);
            if (handler != null) {
                sDefaultHandlers.put(protocol, handler);
                Slog.d(TAG, "Captured default handler for " + protocol + ": " + handler.getClass().getName());
            }
        } catch (Throwable e) {
            Slog.e(TAG, "Failed to capture default handler for " + protocol, e);
        }
    }

    private static class LoggingURLStreamHandlerFactory implements URLStreamHandlerFactory {
        @Override
        public URLStreamHandler createURLStreamHandler(String protocol) {
            if (sDefaultHandlers.containsKey(protocol)) {
                return new LoggingURLStreamHandler(protocol, sDefaultHandlers.get(protocol));
            }
            return null; // Fallback to system default (which won't catch anything if we are the factory, but avoids crash?)
                         // Actually if we return null, URL class tries built-in string handlers.
        }
    }

    private static class LoggingURLStreamHandler extends URLStreamHandler {
        private final String mProtocol;
        private final URLStreamHandler mDelegate;

        public LoggingURLStreamHandler(String protocol, URLStreamHandler delegate) {
            this.mProtocol = protocol;
            this.mDelegate = delegate;
        }

        @Override
        protected URLConnection openConnection(URL u) throws java.io.IOException {
            // Log the URL access
            logUrl(u);
            
            // We cannot easily call mDelegate.openConnection(u) because openConnection is protected.
            // However, since we are a URLStreamHandler, we are expected to return a URLConnection.
            // The delegate (sun.net...) is also a URLStreamHandler.
            // We can use reflection to call the protected openConnection on the delegate.
            try {
                // Use declared method to access protected member
                Method openConnectionMethod = URLStreamHandler.class.getDeclaredMethod("openConnection", URL.class);
                openConnectionMethod.setAccessible(true);
                return (URLConnection) openConnectionMethod.invoke(mDelegate, u);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to delegate openConnection", e);
                throw new java.io.IOException("NetworkHook delegation failed", e);
            }
        }
        
        @Override
        protected URLConnection openConnection(URL u, java.net.Proxy p) throws java.io.IOException {
            logUrl(u);
             try {
                Method openConnectionMethod = URLStreamHandler.class.getDeclaredMethod("openConnection", URL.class, java.net.Proxy.class);
                openConnectionMethod.setAccessible(true);
                return (URLConnection) openConnectionMethod.invoke(mDelegate, u, p);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to delegate openConnection with proxy", e);
                throw new java.io.IOException("NetworkHook delegation failed", e);
            }
        }

        @Override
        protected int getDefaultPort() {
             // We need to implement abstract methods or common overrides
             try {
                Method m = URLStreamHandler.class.getDeclaredMethod("getDefaultPort");
                m.setAccessible(true);
                return (int) m.invoke(mDelegate);
             } catch (Exception e) {
                 return "https".equals(mProtocol) ? 443 : 80;
             }
        }
        
        private void logUrl(URL url) throws java.io.IOException {
            try {
                // Reflection to call NetworkConnectionMonitor.logUrlConnection
                Class<?> monitorClass = Class.forName("com.editech.services.firewall.NetworkConnectionMonitor");
                // Method signature: url, method, status, failureReason, overrideHostname
                Method logMethod = monitorClass.getMethod("logUrlConnection", String.class, String.class, String.class, String.class, String.class);
                
                // We don't know the Method (GET/POST) yet at this stage, default to "REQ"
                logMethod.invoke(null, url.toString(), "REQ", "ESTABLISHED", null, url.getHost());
                
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getTargetException() instanceof java.io.IOException) {
                    throw (java.io.IOException) e.getTargetException();
                }
                // Slog.e(TAG, "Log invocation error", e);
            } catch (Exception e) {
                // Slog.e(TAG, "Log error", e);
            }
        }
    }
}

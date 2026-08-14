package top.niunaijun.blackbox.fake.service;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Hook for Google Play In-App Billing (com.android.vending.billing.IInAppBillingService)
 * Enables community apps checking license/purchases to operate without crashes.
 */
public class IInAppBillingServiceProxy extends BinderInvocationStub {
    public static final String TAG = "IInAppBillingProxy";
    public static final int BILLING_RESPONSE_RESULT_OK = 0;

    public IInAppBillingServiceProxy() {
        super(BRServiceManager.get().getService("inappbilling"));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService("inappbilling");
            if (binder == null) return null;
            Class<?> stubClass = Class.forName("com.android.vending.billing.IInAppBillingService$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            return asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRServiceManager.get().getService("inappbilling") != null) {
            replaceSystemService("inappbilling");
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isBillingSupported")
    public static class IsBillingSupported extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (who != null) {
                    fixPackageArgs(args);
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.d(TAG, "isBillingSupported fallback: " + t.getMessage());
            }
            return BILLING_RESPONSE_RESULT_OK;
        }
    }

    @ProxyMethod("isBillingSupportedExtraParams")
    public static class IsBillingSupportedExtraParams extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (who != null) {
                    fixPackageArgs(args);
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.d(TAG, "isBillingSupportedExtraParams fallback: " + t.getMessage());
            }
            return BILLING_RESPONSE_RESULT_OK;
        }
    }

    @ProxyMethod("getSkuDetails")
    public static class GetSkuDetails extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (who != null) {
                    fixPackageArgs(args);
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.d(TAG, "getSkuDetails fallback: " + t.getMessage());
            }
            Bundle bundle = new Bundle();
            bundle.putInt("RESPONSE_CODE", BILLING_RESPONSE_RESULT_OK);
            bundle.putStringArrayList("DETAILS_LIST", new ArrayList<String>());
            return bundle;
        }
    }

    @ProxyMethod("getPurchases")
    public static class GetPurchases extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (who != null) {
                    fixPackageArgs(args);
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.d(TAG, "getPurchases fallback: " + t.getMessage());
            }
            Bundle bundle = new Bundle();
            bundle.putInt("RESPONSE_CODE", BILLING_RESPONSE_RESULT_OK);
            bundle.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", new ArrayList<String>());
            bundle.putStringArrayList("INAPP_PURCHASE_DATA_LIST", new ArrayList<String>());
            bundle.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", new ArrayList<String>());
            return bundle;
        }
    }

    @ProxyMethod("getPurchasesExtraParams")
    public static class GetPurchasesExtraParams extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (who != null) {
                    fixPackageArgs(args);
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.d(TAG, "getPurchasesExtraParams fallback: " + t.getMessage());
            }
            Bundle bundle = new Bundle();
            bundle.putInt("RESPONSE_CODE", BILLING_RESPONSE_RESULT_OK);
            bundle.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", new ArrayList<String>());
            bundle.putStringArrayList("INAPP_PURCHASE_DATA_LIST", new ArrayList<String>());
            bundle.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", new ArrayList<String>());
            return bundle;
        }
    }

    private static void fixPackageArgs(Object[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof String) {
                    String str = (String) args[i];
                    if (str != null && !str.equals(BlackBoxCore.getHostPkg()) && str.contains(".")) {
                        args[i] = BlackBoxCore.getHostPkg();
                    }
                }
            }
        }
    }
}

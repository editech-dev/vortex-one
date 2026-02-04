package top.niunaijun.blackbox.fake.delegate;

import android.net.Uri;
import android.os.Build;
import android.os.IInterface;
import android.util.ArrayMap;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadProviderClientRecordP;
import black.android.app.BRIActivityManagerContentProviderHolder;
import black.android.content.BRContentProviderHolderOreo;
import black.android.providers.BRSettingsContentProviderHolder;
import black.android.providers.BRSettingsGlobal;
import black.android.providers.BRSettingsNameValueCache;
import black.android.providers.BRSettingsNameValueCacheOreo;
import black.android.providers.BRSettingsSecure;
import black.android.providers.BRSettingsSystem;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.service.context.providers.ContentProviderStub;
import top.niunaijun.blackbox.fake.service.context.providers.SystemProviderStub;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * updated by alex5402 on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 
 */
public class ContentProviderDelegate {
    public static final String TAG = "ContentProviderDelegate";
    private static Set<String> sInjected = new HashSet<>();

    public static void update(Object holder, String auth) {
        top.niunaijun.blackbox.utils.Slog.e(TAG, "update provider: " + auth);
        IInterface iInterface;
        if (BuildCompat.isOreo()) {
            iInterface = BRContentProviderHolderOreo.get(holder).provider();
        } else {
            iInterface = BRIActivityManagerContentProviderHolder.get(holder).provider();
        }

        if (iInterface instanceof Proxy)
            return;
        IInterface bContentProvider;
        switch (auth) {
            case "media":
            case "telephony":
            case "settings":
                bContentProvider = new SystemProviderStub().wrapper(iInterface, BlackBoxCore.getHostPkg());
                break;
            default:
                bContentProvider = new ContentProviderStub().wrapper(iInterface, BlackBoxCore.getHostPkg());
                break;
        }
        if (BuildCompat.isOreo()) {
            BRContentProviderHolderOreo.get(holder)._set_provider(bContentProvider);
        } else {
            BRIActivityManagerContentProviderHolder.get(holder)._set_provider(bContentProvider);
        }
    }

    public static void init() {
        clearSettingProvider();

        BlackBoxCore.getContext().getContentResolver().call(Uri.parse("content://settings"), "", null, null);
        Object activityThread = BlackBoxCore.mainThread();
        ArrayMap<Object, Object> map = (ArrayMap<Object, Object>) BRActivityThread.get(activityThread).mProviderMap();

        top.niunaijun.blackbox.utils.Slog.e(TAG, "Hooking mProviderMap, size: " + (map != null ? map.size() : "null"));

        for (Object value : map.values()) {
            String[] mNames = BRActivityThreadProviderClientRecordP.get(value).mNames();
            String providerName = (mNames != null && mNames.length > 0) ? mNames[0]
                    : "unknown_provider_" + value.hashCode();

            top.niunaijun.blackbox.utils.Slog.e(TAG, "Found provider in map: " + providerName + " (Has names: "
                    + (mNames != null && mNames.length > 0) + ")");

            if (!sInjected.contains(providerName)) {
                sInjected.add(providerName);
                final IInterface iInterface = BRActivityThreadProviderClientRecordP.get(value).mProvider();
                top.niunaijun.blackbox.utils.Slog.e(TAG, "Hooking provider: " + providerName);

                // Use SystemProviderStub if we know it's settings, otherwise
                // ContentProviderStub (which is now safe too)
                // Since name might be unknown, use ContentProviderStub which covers all bases
                // with the fix.
                BRActivityThreadProviderClientRecordP.get(value)
                        ._set_mProvider(new ContentProviderStub().wrapper(iInterface, BlackBoxCore.getHostPkg()));

                // Only set mNames if it was missing? Or leave it?
                // If it was missing, we can't set it to "unknown" as that might break things
                // expecting a real name?
                // But generally safe to leave/set.
                if (mNames == null || mNames.length <= 0) {
                    // Don't set mNames to unknown string, might break logic.
                    // But we MUST set the provider.
                } else {
                    BRActivityThreadProviderClientRecordP.get(value)._set_mNames(new String[] { providerName });
                }
            } else {
                top.niunaijun.blackbox.utils.Slog.e(TAG, "Provider already injected: " + providerName);
            }
        }
    }

    public static void clearSettingProvider() {
        top.niunaijun.blackbox.utils.Slog.e(TAG, "clearSettingProvider start");
        Object cache;
        cache = BRSettingsSystem.get().sNameValueCache();
        if (cache != null) {
            top.niunaijun.blackbox.utils.Slog.e(TAG, "Clearing Settings.System cache");
            clearContentProvider(cache);
        } else {
            top.niunaijun.blackbox.utils.Slog.e(TAG, "Settings.System cache is NULL");
        }
        cache = BRSettingsSecure.get().sNameValueCache();
        if (cache != null) {
            top.niunaijun.blackbox.utils.Slog.e(TAG, "Clearing Settings.Secure cache");
            clearContentProvider(cache);
        } else {
            top.niunaijun.blackbox.utils.Slog.e(TAG, "Settings.Secure cache is NULL");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && BRSettingsGlobal.getRealClass() != null) {
            cache = BRSettingsGlobal.get().sNameValueCache();
            if (cache != null) {
                top.niunaijun.blackbox.utils.Slog.e(TAG, "Clearing Settings.Global cache");
                clearContentProvider(cache);
            } else {
                top.niunaijun.blackbox.utils.Slog.e(TAG, "Settings.Global cache is NULL");
            }
        }
    }

    private static void clearContentProvider(Object cache) {
        if (BuildCompat.isOreo()) {
            Object holder = BRSettingsNameValueCacheOreo.get(cache).mProviderHolder();
            if (holder != null) {
                BRSettingsContentProviderHolder.get(holder)._set_mContentProvider(null);
            }
        } else {
            BRSettingsNameValueCache.get(cache)._set_mContentProvider(null);
        }
    }
}

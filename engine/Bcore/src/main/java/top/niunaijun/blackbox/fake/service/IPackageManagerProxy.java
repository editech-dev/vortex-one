package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.app.ContextImpl;
import black.android.content.pm.BRPackageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.fake.FakeCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext();
        BRContextImpl.get(systemContext).getPackageManager();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            BRPackageManager.get().disableApplicationInfoCache();
            try {
                Reflector.on("android.app.ApplicationPackageManager")
                        .field("mPM")
                        .set(packageManager, proxyInvocation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new SimpleAudioPermissionHook());
        addMethodHook(new CheckSelfPermission());
        addMethodHook(new ShouldShowRequestPermissionRationale());
        addMethodHook(new RequestPermissions());
        addMethodHook(new DisableIconLoading());
        addMethodHook(new SetSplashScreenTheme());
        addMethodHook(new XiaomiSecurityBypass());
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveIntent(intent, resolvedType, flags, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, flags, resolvedType, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    /**
     * gms/gsf/vending are genuinely installed on the real device too (this box
     * needs a real Play Services for its own framework use), and are also
     * registered as virtual apps so guest apps can see them. For those three
     * specifically, ask the REAL PackageManager first: BPackage's own
     * PackageParser is a much older, narrower implementation than the
     * platform's, and a parser that mis-handles the APK Signature Scheme v2/v3
     * block would silently hand back a signature that doesn't match what
     * GoogleApiAvailability expects — exactly the "signature is invalid"
     * symptom this exists to fix. Every other package only exists virtually,
     * so it still resolves through the virtual PackageManager first, falling
     * back to the real one.
     */
    private static Signature[] getSignaturesFor(String pkg) {
        boolean preferRealPm = "com.google.android.gms".equals(pkg)
                || "com.google.android.gsf".equals(pkg)
                || "com.android.vending".equals(pkg);

        if (preferRealPm) {
            Signature[] real = signaturesFromRealPm(pkg);
            if (real != null) return real;
        }

        try {
            PackageInfo pi = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_SIGNATURES, BlackBoxCore.getUserId());
            if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                return pi.signatures;
            }
        } catch (Throwable ignored) {
        }

        return preferRealPm ? null : signaturesFromRealPm(pkg);
    }

    private static Signature[] signaturesFromRealPm(String pkg) {
        try {
            PackageInfo pi = BlackBoxCore.getContext().getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi != null) return pi.signatures;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);

            // Provide fake Google Play Services info for MIUI apps
            if ("com.android.vending".equals(packageName)) {
                return createFakeGooglePlayServicesPackageInfo(flags);
            }
            
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, flags, BlackBoxCore.getUserId());
            if (packageInfo != null) {
                // Patch: Mark RECORD_AUDIO and related permissions as granted
                if (packageInfo.requestedPermissions != null && packageInfo.requestedPermissionsFlags != null) {
                    for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                        String perm = packageInfo.requestedPermissions[i];
                        if (perm != null && (perm.equals(android.Manifest.permission.RECORD_AUDIO)
                                || perm.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                                || perm.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                                || perm.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT))) {
                            packageInfo.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                        }
                    }
                }

                // SPOOF: Set a high version to trick providers/servers
                // We avoid modifying com.android.vending as it is handled separately above
                // 2100000000 is a safe high int (Max is ~2.147B)
                packageInfo.versionCode = 2100000000;
                packageInfo.versionName = "99.99.99";
                if (Build.VERSION.SDK_INT >= 28) { // Android P is 28
                    packageInfo.setLongVersionCode(2100000000L);
                }

                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
        
        private PackageInfo createFakeGooglePlayServicesPackageInfo(int flags) {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = "com.android.vending";
            packageInfo.versionName = "33.8.16-21";
            packageInfo.versionCode = 83381621;

            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = "com.android.vending";
            appInfo.name = "Google Play Store";
            appInfo.flags = ApplicationInfo.FLAG_SYSTEM;
            appInfo.uid = 10001; // System app UID
            packageInfo.applicationInfo = appInfo;

            // This fake PackageInfo never carried a signature, so any caller doing
            // getPackageInfo("com.android.vending", GET_SIGNATURES) — which is
            // exactly what GoogleApiAvailability does to confirm "is this really
            // Play Store" — got back an empty/null signature and reported
            // "requires Google Play Store, but its signature is invalid",
            // regardless of checkSignatures being fixed. Fill it in on request.
            if ((flags & PackageManager.GET_SIGNATURES) != 0) {
                packageInfo.signatures = getSignaturesFor("com.android.vending");
            }

            Slog.d(TAG, "GetPackageInfo: Providing fake Google Play Services info (signed="
                    + (packageInfo.signatures != null) + ")");
            return packageInfo;
        }
    }

    /**
     * checkSignatures() was completely unproxied: it fell through to the real
     * system IPackageManager, which has never heard of a virtual package like
     * com.google.android.gms — it only lives in this sandbox's own
     * BPackageManagerService. That returned SIGNATURE_UNKNOWN_PACKAGE, which is
     * exactly what makes GoogleApiAvailability/GooglePlayServicesUtil report
     * "requires Google Play Store, but its signature is invalid" and bail out
     * before the guest app ever attempts a real connection.
     *
     * The virtualized GMS's signature is never spoofed — generatePackageInfo()
     * already returns whatever the installed GMS APK is genuinely signed with.
     * This hook just makes checkSignatures() resolve both sides against the
     * virtual PackageManager (falling back to the real one) instead of only
     * ever asking the real one.
     */
    @ProxyMethod("checkSignatures")
    public static class CheckSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                String pkg1;
                String pkg2;
                if (args != null && args.length >= 2 && args[0] instanceof String && args[1] instanceof String) {
                    pkg1 = (String) args[0];
                    pkg2 = (String) args[1];
                } else if (args != null && args.length >= 2 && args[0] instanceof Integer && args[1] instanceof Integer) {
                    pkg1 = firstPackageForUid((Integer) args[0]);
                    pkg2 = firstPackageForUid((Integer) args[1]);
                } else {
                    return method.invoke(who, args);
                }

                if (pkg1 == null || pkg2 == null) {
                    return method.invoke(who, args);
                }

                Signature[] s1 = getSignaturesFor(pkg1);
                Signature[] s2 = getSignaturesFor(pkg2);
                int result;
                if (s1 == null || s2 == null) {
                    result = PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                } else {
                    result = signaturesMatch(s1, s2)
                            ? PackageManager.SIGNATURE_MATCH
                            : PackageManager.SIGNATURE_NO_MATCH;
                }
                Slog.d(TAG, "checkSignatures(" + pkg1 + ", " + pkg2 + ") -> " + result
                        + " [s1=" + describe(s1) + " s2=" + describe(s2) + "]");
                return result;
            } catch (Throwable e) {
                Slog.w(TAG, "checkSignatures hook failed, falling back to real binder", e);
                return method.invoke(who, args);
            }
        }

        private String describe(Signature[] sigs) {
            if (sigs == null) return "null";
            StringBuilder sb = new StringBuilder(sigs.length + "x[");
            for (Signature s : sigs) {
                sb.append(Integer.toHexString(s.hashCode())).append(",");
            }
            return sb.append("]").toString();
        }

        private String firstPackageForUid(int uid) {
            try {
                String[] pkgs = BlackBoxCore.getBPackageManager().getPackagesForUid(uid);
                if (pkgs != null && pkgs.length > 0) return pkgs[0];
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** Same semantics as the real checkSignatures(): exact set match, order-independent. */
        private boolean signaturesMatch(Signature[] a, Signature[] b) {
            if (a.length != b.length) return false;
            java.util.Set<Signature> setA = new java.util.HashSet<>(Arrays.asList(a));
            java.util.Set<Signature> setB = new java.util.HashSet<>(Arrays.asList(b));
            return setA.equals(setB);
        }
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().getProviderInfo(componentName, flags, BlackBoxCore.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo receiverInfo = BlackBoxCore.getBPackageManager().getReceiverInfo(componentName, flags, BlackBoxCore.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo activityInfo = BlackBoxCore.getBPackageManager().getActivityInfo(componentName, flags, BlackBoxCore.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo serviceInfo = BlackBoxCore.getBPackageManager().getServiceInfo(componentName, flags, BlackBoxCore.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<ApplicationInfo> installedApplications = BlackBoxCore.getBPackageManager().getInstalledApplications(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<PackageInfo> installedPackages = BlackBoxCore.getBPackageManager().getInstalledPackages(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];

            int flags = MethodParameterUtils.toInt(args[1]);
//            if (ClientSystemEnv.isFakePackage(packageName)) {
//                packageName = BlackBoxCore.getHostPkg();
//            }
            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, flags, BlackBoxCore.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[2]);
            List<ProviderInfo> providers = BlackBoxCore.getBPackageManager().
                    queryContentProviders(BlackBoxCore.getAppProcessName(), BlackBoxCore.getBUid(), flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager().queryBroadcastReceivers(intent, flags, type, BActivityThread.getUserId());
            Slog.d(TAG, "queryIntentReceivers: " + resolves);

            // http://androidxref.com/7.0.0_r1/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#872
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(resolves);
            }

            // http://androidxref.com/6.0.1_r10/xref/frameworks/base/core/java/android/app/ApplicationPackageManager.java#699
            return resolves;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                return method.invoke(who, args);
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = (Integer) args[0];
            if (uid == BlackBoxCore.getHostUid()) {
                args[0] = BActivityThread.getBUid();
                uid = (int) args[0];
            }
            String[] packagesForUid = BlackBoxCore.getBPackageManager().getPackagesForUid(uid);
            Slog.d(TAG, args[0] + " , " + BActivityThread.getAppProcessName() + " GetPackagesForUid: " + Arrays.toString(packagesForUid));
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // fake google play
            return "com.android.vending";
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // todo
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName.getPackageName();
            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName,0, BActivityThread.getUserId());
//            if(applicationInfo == null){
//                throw new IllegalArgumentException();
//            }else{
//                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
//            }
            if(applicationInfo != null){
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            throw new IllegalArgumentException();
        }
    }





    @ProxyMethod("checkPermission")
    public static class SimpleAudioPermissionHook extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            // Handle all audio-related permissions comprehensively
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            // Also grant storage/media read permissions so hosted apps can see device media
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            // Grant notification and Xiaomi permissions automatically
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            // For all other permissions, use the original method
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkSelfPermission")
    public static class CheckSelfPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            // Handle all audio-related permissions comprehensively
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            // Handle storage/media read permissions
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            // Grant notification and Xiaomi permissions automatically
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            // For all other permissions, use the original method
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("shouldShowRequestPermissionRationale")
    public static class ShouldShowRequestPermissionRationale extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            // For audio permissions, don't show rationale since we grant them automatically
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);
                return false;
            }

            // For storage/media, also suppress rationale (we auto-grant)
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for storage/media permission: " + permission);
                return false;
            }
            
            // For notification and Xiaomi permissions, don't show rationale (we auto-grant)
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for notification/Xiaomi permission: " + permission);
                return false;
            }
            
            // For all other permissions, use the original method
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("requestPermissions")
    public static class RequestPermissions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] permissions = (String[]) args[0];
            String packageName = (String) args[1];
            
            // Do not filter permissions here; allow system flow to proceed so apps get callbacks.
            // The actual permission granting is handled by checkPermission/checkSelfPermission hooks
            if (permissions != null) {
                Slog.d(TAG, "RequestPermissions: Allowing permission request flow for: " + java.util.Arrays.toString(permissions));
            }
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDrawable")
    public static class DisableIconLoading extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Prevent icon loading that triggers resource issues
            Slog.d(TAG, "Blocking icon loading to prevent resource errors");
            return null; // Return null instead of trying to load the icon
        }
    }

    // Helper: recognize storage/media read permissions across API levels
    private static boolean isStorageOrMediaPermission(String permission) {
        if (permission == null) return false;
        // Legacy storage & All Files Access
        if (permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                || permission.equals("android.permission.MANAGE_EXTERNAL_STORAGE")) {
            return true;
        }
        // Android 13+ granular media
        if (permission.equals(android.Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.READ_MEDIA_AURAL")
                || permission.equals(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return true;
        }
        // Android 14/15 user-selected media
        if (permission.equals("android.permission.READ_MEDIA_AUDIO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VIDEO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_IMAGES_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_AURAL_USER_SELECTED")) {
            return true;
        }
        return false;
    }

    // Helper: recognize all audio-related permissions across API levels
    private static boolean isAudioPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals("android.permission.FOREGROUND_SERVICE_LOCATION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_HEALTH")
                || permission.equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                || permission.equals("android.permission.FOREGROUND_SERVICE_PHONE_CALL")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
    }
    
    // Helper: recognize notification and Xiaomi-specific permissions
    private static boolean isNotificationOrXiaomiPermission(String permission) {
        if (permission == null) return false;
        
        // Android 12+ notification permission
        if (permission.equals("android.permission.POST_NOTIFICATIONS")) {
            return true;
        }
        
        // Xiaomi-specific permissions
        if (permission.equals("miui.permission.USE_INTERNAL_GENERAL_API") ||
            permission.equals("miui.permission.OPTIMIZE_POWER") ||
            permission.equals("miui.permission.RUN_IN_BACKGROUND") ||
            permission.equals("miui.permission.POST_NOTIFICATIONS") ||
            permission.equals("miui.permission.AUTO_START") ||
            permission.equals("miui.permission.BACKGROUND_POPUP_WINDOW") ||
            permission.equals("miui.permission.SHOW_WHEN_LOCKED") ||
            permission.equals("miui.permission.TURN_SCREEN_ON")) {
            return true;
        }
        
        return false;
    }

    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Bypass UID ownership check for setSplashScreenTheme
            // This method fails with "Calling uid X does not own package Y" in virtual environment
            // Especially problematic on Xiaomi HyperOS (Android 15) with enhanced security
            String packageName = args.length > 0 ? (String) args[0] : "unknown";
            Slog.d(TAG, "SetSplashScreenTheme: Bypassing UID check for package: " + packageName);
            
            // Check if we're on Xiaomi/MIUI system
            boolean isXiaomi = BuildCompat.isMIUI() || 
                              Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                              Build.BRAND.toLowerCase().contains("xiaomi") ||
                              Build.DISPLAY.toLowerCase().contains("hyperos");
                              
            if (isXiaomi) {
                Slog.d(TAG, "SetSplashScreenTheme: Detected Xiaomi/HyperOS, using enhanced bypass");
                // On Xiaomi systems, completely skip the call to avoid security exceptions
                return null;
            }
            
            // For other systems, try to call the original method but catch any SecurityException
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetSplashScreenTheme: SecurityException caught, bypassing: " + e.getMessage());
                return null;
            } catch (Exception e) {
                // If it's wrapped in reflection exception, check the cause
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "SetSplashScreenTheme: SecurityException (wrapped) caught, bypassing: " + e.getCause().getMessage());
                    return null;
                }
                throw e; // Re-throw other exceptions
            }
        }
    }

    // Comprehensive Xiaomi/HyperOS security bypass for multiple methods
    public static class XiaomiSecurityBypass extends MethodHook {
        private static final String[] XIAOMI_SECURITY_METHODS = {
            "setApplicationEnabledSetting",
            "setComponentEnabledSetting", 
            "setInstallLocation",
            "setInstallerPackageName",
            "setPackageStoppedState",
            "setSystemAppState",
            "setApplicationCategoryHint",
            "setApplicationHiddenSettingAsUser",
            "setBlockUninstallForUser",
            "setDefaultBrowserPackageNameAsUser",
            "setDistractingPackageRestrictionsAsUser",
            "setPackagesSuspendedAsUser",
            "setUpdateAvailable",
            "setRequiredForSystemUser",
            "setSystemAppHiddenUntilInstalled",
            "setHarmfulAppWarningEnabled",
            "setKeepUninstalledPackages",
            "verifyIntentFilter",
            "verifyPendingInstall",
            "extendVerificationTimeout",
            "setDefaultHomeActivity",
            "resetApplicationPreferences",
            "clearApplicationProfileData",
            "clearApplicationUserData",
            "deleteApplicationCacheFiles",
            "deleteApplicationCacheFilesAsUser",
            "freeStorageAndNotify",
            "freeStorage",
            "movePackage",
            "movePackageToSd",
            "movePrimaryStorage"
        };

        @Override
        public boolean isEnable() {
            // Only enable on Xiaomi/HyperOS systems
            return BuildCompat.isMIUI() || 
                   Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                   Build.BRAND.toLowerCase().contains("xiaomi") ||
                   Build.DISPLAY.toLowerCase().contains("hyperos");
        }

        @Override
        public String getMethodName() {
            return null; // We handle multiple methods
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            // Check if this is a Xiaomi security-sensitive method
            for (String securityMethod : XIAOMI_SECURITY_METHODS) {
                if (securityMethod.equals(methodName)) {
                    Slog.d(TAG, "XiaomiSecurityBypass: Intercepting " + methodName + " on Xiaomi/HyperOS");
                    
                    // For methods that return void, just return null
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    // For methods that return boolean, return true (success)
                    else if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    // For methods that return int, return 0 (success)
                    else if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    // For other return types, return null
                    else {
                        return null;
                    }
                }
            }
            
            // Not a security method, proceed normally but catch SecurityExceptions
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "XiaomiSecurityBypass: SecurityException in " + methodName + ", bypassing: " + e.getMessage());
                // Return appropriate default based on return type
                if (method.getReturnType() == boolean.class) {
                    return false;
                } else if (method.getReturnType() == int.class) {
                    return -1;
                } else {
                    return null;
                }
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "XiaomiSecurityBypass: SecurityException (wrapped) in " + methodName + ", bypassing: " + e.getCause().getMessage());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    } else if (method.getReturnType() == int.class) {
                        return -1;
                    } else {
                        return null;
                    }
                }
                throw e;
            }
        }
    }
}

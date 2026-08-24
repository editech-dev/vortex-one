# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────────────────
# Release minification was never exercised on this project before v2.0.3 —
# these rules exist because the virtualization engine leans on reflection
# and JNI far more than a typical app, and R8 has no way to see those call
# sites. Getting one of these wrong doesn't show up as a compile error; it
# shows up as a runtime ClassNotFoundException or UnsatisfiedLinkError deep
# inside a sandboxed guest process. Verify on-device after any change here,
# not just a clean build.
# ─────────────────────────────────────────────────────────────────────────

# :engine:Bcore (BlackBox-derived virtualization core). HookManager resolves
# @ProxyMethod-annotated inner classes, AIDL Stub/Proxy implementations, and
# the black.** hidden-API reflection shims entirely by class/method name at
# runtime — there is no static call site R8 could trace. Kept wholesale
# rather than enumerated: the surface is too broad and changes too often
# for a hand-picked list to stay correct.
-keep class top.niunaijun.blackbox.** { *; }
-keep interface top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep class black.** { *; }
-keep interface black.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-dontwarn top.niunaijun.**
-dontwarn black.**

# Locally-compiled AIDL stubs living under platform package names
# (android.app.IActivityManager, android.content.**, etc.) — these are
# real compiled classes in this app's own dex, not the platform's hidden
# implementation, and HookManager/BR* wrappers address them by exact name.
-keep class android.app.** { *; }
-keep interface android.app.** { *; }
-keep class android.content.** { *; }
-keep interface android.content.** { *; }
-keep class android.os.** { *; }
-keep interface android.os.** { *; }
-keep class android.net.** { *; }
-keep interface android.net.** { *; }
-keep class android.location.** { *; }
-keep interface android.location.** { *; }
-keep class android.database.** { *; }
-keep interface android.database.** { *; }
-keep class android.accounts.** { *; }
-keep interface android.accounts.** { *; }
-dontwarn android.app.**
-dontwarn android.content.**

# app-module classes the engine reaches into by Class.forName(), since
# :engine:Bcore can't depend on :app directly. Method names are looked up
# by exact string (getMethod("isTorEnabledForPackage", ...), etc.) —
# renaming any of these breaks Tor routing, DoH resolution, or firewall
# logging silently (caught by a broad try/catch, so it fails as "network
# blocked" with no stack trace, not as a crash).
-keep class com.editech.services.tor.TorManager { *; }
-keep class com.editech.services.net.CloudflareDnsResolver { *; }
-keep class com.editech.services.net.CloudflareDnsResolver$* { *; }
-keep class com.editech.services.firewall.NetworkConnectionMonitor { *; }
-keep class com.editech.services.firewall.BandwidthManager { *; }
-keep class com.editech.services.utils.PrimeVideoBootstrapFix { *; }
-keep class com.editech.services.utils.TarExtractor { *; }
-keep class com.editech.services.utils.GmsCore { *; }

# JNI: native method signatures are bound by exact name from the C++ side
# (System.loadLibrary("blackbox")); R8 renaming either the class or the
# method throws UnsatisfiedLinkError at the first native call, not at
# build time.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class top.niunaijun.blackbox.core.NativeCore { *; }

# Firewall Room database: entities/DAOs are usually covered by Room's own
# consumer-proguard-rules, but keep them explicitly since connection logs
# are read back via reflection-friendly generated bindings too.
-keep class com.editech.services.firewall.database.** { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# Unity Ads bundles its own protobuf-lite runtime and generated config
# messages under gateway.v1.**, outside the com.unity3d.** namespace its own
# proguard.txt covers — protobuf-lite's GeneratedMessageLite reads/writes
# fields by exact declared name via reflection (MessageSchema), so R8
# renaming gateway.v1.NativeConfigurationOuterClass$AdOperationsConfiguration
# down to a single letter broke Unity Ads init on the very first release
# build with "Field loadTimeoutMs_ for xf0 not found". Confirmed fixed by
# keeping both the generated messages and the runtime schema mechanism they
# depend on (the official protobuf-lite proguard recommendation).
-keep class gateway.v1.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn gateway.v1.**

# Standard Android boilerplate for anything reflection- or serialization-
# adjacent that survives minification.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

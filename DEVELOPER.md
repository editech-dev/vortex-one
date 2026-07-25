# Developer Documentation - Vortex One

This document contains detailed technical information about the architecture, virtualization engine, Tor network integration, firewall inspection, and build process of **Vortex One**.

---

## 🛠️ Technology Stack & Dependencies

- **Language**: Kotlin 1.9 (App & Firewall) + Java 8/17 (Virtualization Engine Core)
- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34 (Android 14)
- **UI**: XML ViewBinding + Material Components (No Compose for optimum Leanback rendering speed)
- **Virtualization Engine**: BlackBox Core (`:engine:Bcore`, Apache 2.0)
- **Embedded Network Engine**: `info.guardianproject.tor:tor-android` (Native Tor binary)
- **Database**: Room Persistence Library (SQLite) with auto-pruning
- **Architectures**: ARM64-v8a, ARMeabi-v7a

---

## 🏗️ Architecture & Multi-Process Model

Vortex One runs in a multi-process architecture to isolate virtualized applications from the host UI and guarantee network security:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Main Process (com.editech.services)               │
│                                                                             │
│   ┌─────────────────────┐   ┌───────────────────────┐   ┌───────────────┐   │
│   │ MainActivity (Grid) │   │ FirewallAppDetailAct  │   │  TorService   │   │
│   └──────────┬──────────┘   └───────────┬───────────┘   │ (SOCKS5 9150) │   │
│              ▼                          ▼               └───────▲───────┘   │
│       VirtualApp Mgr               TorFragment                  │           │
│              │                          │ (LiveData)            │           │
│              ▼                          └───────────────┬───────┘           │
└──────────────┼──────────────────────────────────────────┼───────────────────┘
               │ IPC / BActivityThread                   │ Localhost Socket
               ▼                                          │
┌──────────────────────────────────────────────┐          │
│ Sandbox Process (com.editech.services:p0)    │          │
│                                              │          │
│   ┌──────────────────────────────────────┐   │          │
│   │  Virtual Application (e.g. YouTube)  │   │          │
│   └──────────────────┬───────────────────┘   │          │
│                      ▼ Libcore Socket Hook   │          │
│                 OsStub.java                  │          │
│           (connect, sendto, recvfrom)        │          │
│                      │                       │          │
│                      └───────────────────────┴──────────┘
```

### Process Isolation Breakdown
1. **`com.editech.services` (Main Process)**: Holds the UI (`MainActivity`, `FirewallActivity`, `TorFragment`) and runs `TorService` (Foreground service executing the native Tor daemon listening on `127.0.0.1:9150`).
2. **`com.editech.services:black` (Server Process)**: Core BlackBox server daemon managing virtual app lifecycle, user IDs, package installation, and IPC bindings.
3. **`com.editech.services:p0...pN` (Client Processes)**: Isolated sandbox environments where virtualized applications run. Native socket calls (`connect`, `sendto`, `recvfrom`) are intercepted here by `OsStub.java`.

---

## 🔧 Project Structure

```
VortexOne/
├── app/src/main/
│   └── java/com/editech/services/
│       ├── App.kt                        # App init (BlackBox & TorManager init)
│       ├── MainActivity.kt               # Main TV/Mobile Dashboard
│       ├── activities/
│       │   ├── FileScannerActivity.kt     # APK filesystem scanner
│       │   ├── FirewallActivity.kt        # Global Firewall & Logs Activity
│       │   ├── FirewallAppDetailActivity.kt # App Detail (Ports, Tor, Logs)
│       │   ├── SettingsActivity.kt        # Storage, Cache & App Info
│       │   ├── TorFragment.kt             # Per-App Tor Control Fragment
│       │   └── SystemAppsActivity.kt      # System app cloner
│       ├── firewall/
│       │   ├── BandwidthManager.kt        # Speed throttling (Tx/Rx)
│       │   ├── ConnectionLog.kt           # Log entry model
│       │   ├── FirewallManager.kt         # Rule evaluator & DB manager
│       │   ├── NetworkConnectionMonitor.kt # Socket logger & threat inspector
│       │   └── database/                  # Room Database
│       │       ├── ConnectionLogDao.kt    # Includes 7-day auto-pruning
│       │       ├── FirewallDatabase.kt
│       │       └── FirewallRuleDao.kt
│       ├── tor/
│       │   ├── TorManager.kt              # Singleton tracking per-app Tor state
│       │   └── TorService.kt              # Foreground Service running tor binary
│       └── utils/
│           ├── AdManager.kt              # Unity Ads manager
│           ├── LocaleHelper.kt           # Dynamic language switcher
│           └── StorageUtils.kt           # Cache cleaner & storage stats
├── engine/                                # Virtualization Engine Modules
│   ├── Bcore/                             # Core engine library (Java/C++)
│   │   └── src/main/java/top/niunaijun/blackbox/fake/service/libcore/
│   │       └── OsStub.java                # Libcore socket hooks (Tor & Firewall)
│   ├── black-reflection/                  # Reflection utilities
│   └── compiler/                          # Annotation processors
├── README.md
└── DEVELOPER.md                           # Technical Developer Documentation
```

---

## 🛡️ Deep-Dive: Tor & Firewall Hook Architecture (`OsStub.java`)

Network interception takes place inside `OsStub.java` (in `:engine:Bcore`), which hooks system-level `Os.connect`, `Os.sendto`, and `Os.recvfrom`:

```java
// 1. Firewall Rule Check
boolean shouldBlock = (boolean) checkMethod.invoke(null, address, port);
if (shouldBlock) {
    throw new SocketException("Connection blocked by firewall");
}

// 2. Tor Per-App Redirection
String pkg = BActivityThread.getAppPackageName();
boolean torEnabled = TorManager.isTorEnabledForPackage(pkg);
if (torEnabled) {
    boolean proxyUp = TorManager.isProxyReachable();
    if (!proxyUp) {
        // Grace Period: 3 retries x 400ms delay to allow Tor bootstrap
        for (int retry = 0; retry < 3; retry++) {
            Thread.sleep(400);
            if (TorManager.isProxyReachable()) { proxyUp = true; break; }
        }
    }
    if (!proxyUp) {
        throw new SocketException("[Tor] Proxy not ready — connection blocked for safety");
    }
    // Tunnel socket FileDescriptor transparently through SOCKS5 127.0.0.1:9150
    return connectViaTorSocks5(who, method, args, address, port, pkg);
}
```

### Main Thread Safety
Calls to `TorManager.isProxyReachable()` from the Main UI Thread automatically dispatch the socket ping onto an isolated `SingleThreadExecutor` with a 400ms timeout to prevent `android.os.NetworkOnMainThreadException`.

---

## 🚀 Build & Compilation Guide

### Prerequisites
- JDK 17
- Android SDK 34 (Build Tools 34.0.0)
- NDK 25.x (Required for native BlackBox C++ hooks)

### Gradle Build Commands

```bash
# Build Debug APKs (with full logcat output)
./gradlew assembleDebug

# Build Production Release APKs (Obfuscated with ProGuard/R8)
./gradlew clean assembleRelease

# Clean output directories
./gradlew clean
```

### Release Outputs
Compiled release binaries are placed in `app/build/outputs/apk/release/`:
- `app-universal-release.apk`
- `app-arm64-v8a-release.apk`
- `app-armeabi-v7a-release.apk`

---

## 📺 Android TV D-Pad Guidelines

When creating or updating UI screens in Vortex One:
1. **Focus Highlight**: Ensure cards use `setOnFocusChangeListener` to toggle border stroke (`#38BDF8`).
2. **Key Interception**: Override `dispatchKeyEvent(event)` in `Activity` for custom tab-to-list navigation when D-Pad `KEYCODE_DPAD_DOWN` is pressed.
3. **RecyclerView Focus**: Set `recyclerView.isFocusable = false` and `descendantFocusability = FOCUS_AFTER_DESCENDANTS`.

---

*Developer Guide for Vortex One v1.0.4.*

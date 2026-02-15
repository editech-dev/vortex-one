# Developer Documentation - Vortex One

This document contains detailed technical information about the architecture, build process, and development of **Vortex One** (formerly OpenContainer-TV).

## 🛠️ Technology Stack

- **Language**: Kotlin (app) + Java (virtualization engine core)
- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34
- **UI**: XML with ViewBinding (No Jetpack Compose for better performance on TV)
- **Virtualization Engine**: Based on BlackBox (Apache 2.0) — see [NOTICE](NOTICE)
- **Architectures**: ARM64-v8a, ARMeabi-v7a

## 🏗️ Architecture

```
┌─────────────────────────────────────────┐
│          MainActivity (Dashboard)        │
│  ┌─────────────────────────────────────┐│
│  │  RecyclerView (4-Column Grid)       ││
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐        ││
│  │  │App1│ │App2│ │App3│ │App4│        ││
│  │  └────┘ └────┘ └────┘ └────┘        ││
│  └─────────────────────────────────────┘│
│          ▼                    ▼          │
│    FileScannerActivity   VirtualApp Mgr  │
│          ▼                    ▼          │
└─────────┬───────────────────┬────────────┘
          ▼                   ▼
    ┌──────────┐      ┌──────────────┐
    │APK Scanner│      │ Engine (Bcore)│
    └──────────┘      └──────────────┘
```

## 🔧 Project Structure

```
VortexOne/
├── app/src/main/
│   └── java/com/editech/services/
│       ├── App.kt                        # Application class
│       ├── MainActivity.kt               # Main Dashboard
│       ├── activities/
│       │   ├── FileScannerActivity.kt     # APK filesystem scanner
│       │   ├── FirewallActivity.kt        # Firewall management
│       │   ├── FirewallAppDetailActivity.kt
│       │   └── SystemAppsActivity.kt      # System app virtualizer
│       ├── adapters/
│       │   ├── VirtualAppsAdapter.kt      # App Grid Adapter
│       │   ├── ApkFileAdapter.kt          # APK List Adapter
│       │   └── SystemAppsAdapter.kt
│       ├── firewall/
│       │   ├── ConnectionLog.kt           # Connection log model
│       │   ├── FirewallManager.kt         # Core firewall logic
│       │   ├── FirewallRule.kt            # Rule model + enums
│       │   ├── NetworkConnectionMonitor.kt
│       │   └── database/                  # Room database (Kotlin)
│       │       ├── ConnectionLogDao.kt
│       │       ├── ConnectionLogEntity.kt
│       │       ├── FirewallAppStateEntity.kt
│       │       ├── FirewallDatabase.kt
│       │       ├── FirewallRuleDao.kt
│       │       └── FirewallRuleEntity.kt
│       ├── models/
│       │   ├── VirtualApp.kt
│       │   ├── ApkFile.kt
│       │   └── SystemApp.kt
│       └── utils/
│           ├── AdManager.kt              # Ad Utils (Unity Ads)
│           └── FirewallBridge.kt
├── engine/                                # Virtualization Engine (Apache 2.0)
│   ├── Bcore/                             # Core virtualization library (Java)
│   ├── black-reflection/                  # Reflection utilities
│   └── compiler/                          # Annotation processor
├── LICENSE                                # MIT (Vortex One) + Apache 2.0 credits
├── NOTICE                                 # BlackBox attribution
├── README.md
└── DEVELOPER.md                           # This file
```

> [!NOTE]
> The `engine/` directory contains the BlackBox-based virtualization engine.
> Its Java code is intentionally kept in Java — these are low-level Android
> framework stubs, AIDL interfaces, and JNI bindings that require exact Java
> signatures. See the [NOTICE](NOTICE) file for full attribution.

## ⚙️ Engine Integration

The virtualization engine (based on BlackBox, Apache 2.0) is integrated as
local Gradle modules:

```kotlin
// settings.gradle.kts
include(":engine:Bcore")
include(":engine:black-reflection")
include(":engine:compiler")

// app/build.gradle.kts
implementation(project(":engine:Bcore"))
```

## 🚀 Build Guide

### Prerequisites
- JDK 17 or higher
- Android Studio Koala or higher
- Android TV or Fire TV device connected via ADB

### Gradle Commands

```bash
# Build Debug variant (with logs)
./gradlew assembleDebug

# Build Release variant (obfuscated and optimized)
./gradlew assembleRelease

# Clean project
./gradlew clean
```

### Installation via ADB
```bash
./gradlew installDebug
# Or manually:
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🎨 Customization

### Application Grid
To change the number of columns in the main dashboard:
Modify `MainActivity.kt`:
```kotlin
layoutManager = GridLayoutManager(this, 3) // Change to 3 columns
```
And update `res/values/integers.xml` if `grid_span_count` is referenced.

### Ads
Ad management is centralized in `utils/AdManager.kt`.
- **Interstitial Frequency**: Adjust `MIN_TIME_BETWEEN_ADS_MS` (Default: 4 hours).
- **Ad IDs**: Update `GAME_ID`, `BANNER_ID`, etc., with your Unity Ads IDs.

## 🐛 Known Issues

- **BlackBox Stub**: If compiling without the real BCore module, virtualization functions will throw controlled exceptions.
- **Permissions**: On Android 11+ (API 30+), `Manage External Storage` access is critical for APK installation.

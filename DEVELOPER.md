# Developer Documentation - Vortex One

This document contains detailed technical information about the architecture, build process, and development of **Vortex One** (formerly OpenContainer-TV).

## 🛠️ Technology Stack

- **Language**: Kotlin 100%
- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34
- **UI**: XML with ViewBinding (No Jetpack Compose for better performance on TV)
- **Virtualization Engine**: BlackBox
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
    │APK Scanner│      │ BlackBox Core│
    └──────────┘      └──────────────┘
```

## 🔧 Project Structure

```
app/src/main/
├── java/com/editech/services/
│   ├── App.kt                     # Application class (BlackBox & Ads Init)
│   ├── MainActivity.kt            # Main Dashboard
│   ├── activities/
│   │   ├── FileScannerActivity.kt # APK fs scanner
│   │   └── SystemAppsActivity.kt  # System app virtualizer
│   ├── adapters/
│   │   ├── VirtualAppsAdapter.kt  # App Grid Adapter
│   │   └── ApkFileAdapter.kt      # APK List Adapter
│   ├── models/
│   │   ├── VirtualApp.kt
│   │   └── ApkFile.kt
│   └── utils/
│       └── AdManager.kt           # Ad Utils (Unity Ads)
└── res/
    ├── layout/                    # Standardized XML Layouts
    └── drawable/                  # Drawable resources
```

## ⚙️ BlackBox Integration

> [!IMPORTANT]
> The project must integrate the BlackBox engine for virtualization.

1. **Download BlackBox**: `git clone https://github.com/FBlackBox/BlackBox.git`
2. **Integrate**: Can be added as a module (`:Bcore`) or as an AAR in `app/libs`.
3. **Dependencies**: Ensure `implementation("com.github.FBlackBox:BlackBox:0.6.0")` or the project reference is active in `build.gradle.kts`.

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

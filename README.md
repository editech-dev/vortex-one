# Vortex One 📱📺

[![Release](https://img.shields.io/badge/Release-v2.0.2-brightgreen?style=for-the-badge&logo=github)](https://github.com/editech-dev/vortex-one/releases/tag/v2.0.2)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Mobile-blue?style=for-the-badge&logo=android)](https://github.com/editech-dev/vortex-one)
[![Privacy](https://img.shields.io/badge/Privacy-Tor%20Embedded%20%2B%20Direct--IP%20DoH-purple?style=for-the-badge&logo=torbrowser)](https://github.com/editech-dev/vortex-one)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange?style=for-the-badge)](NOTICE)

**Vortex One** is a next-generation Android application virtualization engine, privacy suite, and sandbox security hub designed seamlessly for both **Android TV** and **Mobile Devices**.

Run community streaming apps, IPTV players, emulators, and cloned APKs with complete file-system and network isolation. Protect your identity with per-app **Tor routing**, encrypted **DNS-over-HTTPS (DoH)** with zero DNS recursion, real-time **Firewall inspection**, in-app update checks from GitHub Releases, and native 60 FPS hardware video decoding.

---

## 📥 Downloads (v2.0.2)

Choose the optimal package for your target device:

| Architecture | Recommended Devices | Direct Download Link |
| :--- | :--- | :--- |
| 🌐 **Universal APK** | Smart TVs, TV Boxes, Smartphones (All) | [Download Universal v2.0.2](https://github.com/editech-dev/vortex-one/releases/download/v2.0.2/VortexOne-v2.0.2-universal.apk) |
| ⚡ **ARM64 64-bit** | Modern Smartphones & High-end TV Boxes | [Download ARM64 v2.0.2](https://github.com/editech-dev/vortex-one/releases/download/v2.0.2/VortexOne-v2.0.2-arm64-v8a.apk) |
| 📺 **ARMv7 32-bit** | Smart TVs, TV Sticks & Onn 4K Streaming Boxes | [Download ARMv7 v2.0.2](https://github.com/editech-dev/vortex-one/releases/download/v2.0.2/VortexOne-v2.0.2-armeabi-v7a.apk) |

---

## 📸 Interface Preview (Android TV 16:9 Experience)

Capturas de pantalla reales capturadas directamente desde **Onn 4K Streaming Box (Android 14)**:

| 📺 Home Dashboard | ⚙️ Storage, GMS & Updates | 🛡️ Firewall & Tor Privacy |
| :---: | :---: | :---: |
| ![TV Home](docs/images/tv/device1.png) | ![TV Settings](docs/images/tv/device2.png) | ![TV Firewall](docs/images/tv/device3.png) |
| *Grilla 16:9 con navegación D-Pad y apps comunitarias aisladas* | *Mantenimiento de almacenamiento, servicios Google y actualizador* | *Nodo de salida Tor con bandera en vivo y reglas de red* |

---

## ✨ Core Features & Technical Highlights

### 🛡️ 1. Embedded Tor Network Privacy Suite & Exit Node Verification
- **Per-App Tor Routing**: Enable or disable Tor routing per virtual app with zero interference with host system network traffic.
- **Embedded Tor Daemon**: Runs an embedded native Tor binary (`libtor.so`) with local SOCKS5 proxy (`127.0.0.1:9150`) and DNS listener (`127.0.0.1:5453`).
- **Live Exit Node Tracking**: Dynamically queries and displays the current Tor exit node IP and Country Flag (e.g. `🇩🇪 Germany · 45.13.225.69` or `🇳🇱 Netherlands · 192.42.116.50`) on the app splash loader and Tor settings panel.
- **Dynamic Identity Renewal**: One-click "Cambiar de enlace" action triggers `SIGNAL NEWNYM` to establish a brand-new Tor circuit.
- **Zero DNS Leaks & ISP Block Bypass**: Libcore socket interception ([`OsStub.java`](file:///home/edison/AndroidStudioProjects/MediaService/engine/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/libcore/OsStub.java)) maps domain names to local virtual IPs (`127.192.0.0/16`), resolving all queries remotely through Tor exit nodes (`ATYP 0x03`).
- **Fail-Safe Kill-Switch**: Automatically blocks unencrypted IP leaks if the Tor daemon disconnects or fails.

### 🔒 2. Zero-Recursion Direct-IP DNS-over-HTTPS (DoH / RFC 8484)
- **Direct-IP DoH Connections**: Resolves domain names for non-Tor apps directly through IP endpoints (`1.1.1.1:443`, `1.0.0.1:443`, `8.8.8.8:443`, `9.9.9.9:443`).
- **Zero DNS Recursion Leaks**: Completely eliminates intermediate system DNS / DoT queries for DoH hostnames, ensuring local ISPs and DNS services cannot monitor app queries.
- **High-Speed In-Memory LRU Cache**: Sub-millisecond repeat resolutions with 5-minute TTL.

### 🔄 3. In-App Updates via GitHub Releases
- **User-Initiated Check**: In Settings, a dedicated "Buscar actualizaciones" button queries the public GitHub Releases API.
- **Architecture-Aware APK Matcher**: Automatically detects device ABI (`Build.SUPPORTED_ABIS`) and selects the optimal `.apk` (`armeabi-v7a` for 32-bit TV boxes, `arm64-v8a` for 64-bit devices, or `universal`).
- **TV-Optimized Update Modal**: Displays formatted release notes (changelog), file size, download progress bar, and launches the native Android installer using `androidx.core.content.FileProvider`.

### 🧩 4. Google Play Services (GMS) & In-App Billing Bridge
- **`IGmsServiceBroker` Reflection Bridge**: Dynamic parameter reflection in [`GmsProxy.java`](file:///home/edison/AndroidStudioProjects/MediaService/engine/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/GmsProxy.java) enables **Google Sign-In**, Firebase Auth, and Google Maps in virtual apps without security crashes.
- **Google Cast Support**: Socket and mDNS pass-through allows community media players to stream video to Chromecast/Google TV devices.
- **In-App Billing Stub**: Responds to license verification calls for Pro/Premium community tools.
- **Clean UI Filtering**: Infrastructure packages run silently in the background and are excluded from the main launcher grid.

### 🚀 5. Android TV Leanback & 60 FPS Hardware Acceleration
- **Native Leanback Launch Intent**: Resolves `Intent.CATEGORY_LEANBACK_LAUNCHER` for TV-only applications.
- **Mali / Amlogic Hardware Decoding**: Exposes true SoC properties in [`VirtualSpoof.cpp`](file:///home/edison/AndroidStudioProjects/MediaService/engine/Bcore/src/main/cpp/Utils/VirtualSpoof.cpp) for native ExoPlayer, VLC, and ijkplayer hardware acceleration at full 60 FPS.
- **Android 13/14 Compatibility**: Dedicated [`ILocaleManagerProxy.java`](file:///home/edison/AndroidStudioProjects/MediaService/engine/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ILocaleManagerProxy.java) prevents modern IPC `SecurityException` errors.

### 🔥 6. Integrated Firewall & Traffic Monitor
- **Socket Level Inspection**: Monitor inbound and outbound TCP/UDP traffic per package.
- **Custom Rule Engine**: Block specific ports, endpoints, and telemetry servers.
- **Bandwidth Throttling**: Configure custom Upload/Download speed limits per app.
- **Room Database with Auto-Pruning**: Automated 7-day log retention keeping memory usage under 3.5%.

---

## 📜 Version 2.0.2 Changelog

> [!NOTE]
> **Key Enhancements in v2.0.2:**
> - **Zero-Leak Direct-IP DoH Resolver**: Upgraded `CloudflareDnsResolver` to connect directly to `1.1.1.1:443`, `8.8.8.8:443`, and `9.9.9.9:443` with custom TLS trust managers, completely eliminating DNS recursion leaks to local ISP/NextDNS for non-Tor apps.
> - **Tor Exit Node UI & Live Verification**: Integrated real-time Tor exit node IP and country emoji flag badge into `LauncherActivity` (splash loader) and `TorFragment` (Tor settings).
> - **Dynamic Identity Renewal**: Added interactive "Cambiar de enlace" button in Tor settings that sends `SIGNAL NEWNYM` to rebuild the Tor circuit on demand.
> - **In-App Updater from GitHub Releases**: Added manual update checker in Settings that queries GitHub Releases, downloads architecture-specific APKs (`armeabi-v7a` / `arm64-v8a` / `universal`) with download progress, and triggers native package installation via `FileProvider`.
> - **Process-Wide Package Caching & Crash Fixes**: Enforced volatile process-level package caching in `OsStub` for virtual threads, wrapped native dynamic proxy invocations to safely unwrap `InvocationTargetException`, and hardened the Tor killswitch against SSDP/UPnP leaks.

---

## 📜 Version 2.0.1 Changelog
 
> [!NOTE]
> **Key Enhancements in v2.0.1:**
> - **In-App Sandbox Updates**: Added automated internal processing of `Intent.ACTION_VIEW` (`application/vnd.android.package-archive`) within `ClientConfiguration.requestInstallPackage`.
> - **Read-Only Overwrite Fix (EACCES)**: Fixed `CopyExecutor` and `NativeUtils` to safely clear read-only permissions (`chmod 600` / `delete`) on target `base.apk` and `.so` libraries before performing updates.
> - **Version Enforcement & Protection**: Integrated strict package verification preventing downgrade attacks (`newVersionCode >= currentVersionCode`), host package spoofing, and unsupported ABI installations.
> - **StreamBridge & Streaming App Support**: Fully verified smooth execution and seamless in-app updates for live TV streaming apps (StreamBridge, etc.) with hardware-accelerated video decoding.

---

## 📜 Version 2.0.0 Changelog

> [!NOTE]
> **Key Enhancements in v2.0.0:**
> - **Android TV Leanback Support**: Full compatibility with TV-only apps via `CATEGORY_LEANBACK_LAUNCHER`.
> - **Google Play Services Virtualization**: Added `GmsProxy` reflection and `IInAppBillingServiceProxy` for Cast, Auth, and Purchases.
> - **DNS-over-TLS (DoT)**: Added `CloudflareDnsResolver` with RFC 7858 TLS encryption on port 853.
> - **GPU Hardware Pass-through**: Cleaned `VirtualSpoof.cpp` to enable native Mali GPU 60 FPS video decoding on Amlogic chipsets.
> - **LocaleManager Hook**: Added `ILocaleManagerProxy` for seamless Android 14 (API 34) execution.
> - **Streamlined UI/UX**: Filtered internal infrastructure packages from the launcher, settings, and firewall lists.
> - **Zero-Leak Tor Privacy**: Integrated remote DNS resolution with virtual IP mapping and fail-safe Kill-Switch.

---

## 🚀 Getting Started

1. **Install Vortex One**: Download the APK matching your device architecture.
2. **Add Applications**:
   - Click **"System Apps"** to clone apps already installed on your host device.
   - Click **"Install APK"** to import `.apk` files directly from internal storage or a USB drive.
3. **Configure Privacy & Firewall**:
   - Open **Firewall** -> Select an app -> Go to **Tor** tab to activate Tor protection.
   - Monitor live traffic under **Logs**.
4. **Manage Google Services**:
   - Open **Settings** -> Toggle **Google Play Services** on or off per virtual user.
5. **Check for Updates**:
   - Open **Settings** -> Click **"Buscar actualizaciones"** to verify if a new release is available on GitHub.
6. **Launch**: Click any cloned app card to start its isolated virtual session.

---

## 👨‍💻 Technical Documentation

For developers, contributors, and detailed architecture notes (Virtual Engine, Libcore Hooks, Inter-Process Communication):

👉 **[Read Developer Guide (DEVELOPER.md)](DEVELOPER.md)**  
👉 **[Read AI Agents Directory (agents.md)](agents.md)**

---

## 🙏 Acknowledgments

Vortex One relies on these outstanding open-source projects:

- **[BlackBox](https://github.com/FBlackBox/BlackBox)** — Virtual engine by **ALEX502** (Apache 2.0). Core virtualization layer.
- **[Tor Android](https://github.com/guardianproject/tor-android)** — Embedded Tor binaries by Guardian Project.
- **VirtualApp / VirtualAPK** — Original virtualization concepts.
- **Dobby & xDL** — Native inline hook framework and dynamic linker utilities.

---

*Built with ❤️ by [editech-dev](https://github.com/editech-dev) for the Android Community.*

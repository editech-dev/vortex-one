# Vortex One 📱📺

**Vortex One** is your ultimate entertainment and virtualization hub, designed to work perfectly on both **Android TV** and **Mobile Phones**.
It allows you to run multiple accounts of your favorite apps and manage APKs easily, with an adaptive interface that adjusts to your screen.

---

## 📸 Gallery

Explore the versatility of Vortex One in different scenarios:

### 📺 Android TV Experience

Vortex One is optimized for the big screen, with smooth remote control navigation.

| Home Screen | App Management | Installation |
|:---:|:---:|:---:|
| ![TV Home](docs/images/tv/device1.png) | ![TV Apps](docs/images/tv/device2.png) | ![TV Dialog](docs/images/tv/device3.png) |

### 📱 Mobile Experience

Take virtualization with you. Vortex One adapts to your phone screen for intuitive touch control.

| Portrait View | Control Panel | Options Menu |
|:---:|:---:|:---:|
| ![Mobile Home](docs/images/mobile/mobile1.png) | ![Mobile Apps](docs/images/mobile/mobile2.png) | ![Mobile Dialog](docs/images/mobile/mobile3.png) |

---

## 📥 Download

Get the latest version of Vortex One:

[![Download APK](https://img.shields.io/badge/Download-v1.0.4-brightgreen?style=for-the-badge&logo=android)](https://github.com/editech-dev/vortex-one/releases/download/v1.0.4/VortexOne-v1.0.4-universal.apk)

Or download directly: [VortexOne-v1.0.4-universal.apk](https://github.com/editech-dev/vortex-one/releases/download/v1.0.4/VortexOne-v1.0.4-universal.apk)

---

## 📜 Novedades de la Versión v1.0.4 (Release Notes)

- **🛡️ Integración y Estabilidad de Red Tor**:
  - **Auto-Inicio de Servicio**: El servicio Tor arranca automáticamente en segundo plano al abrir Vortex One si alguna app virtual tiene Tor habilitado.
  - **Sincronización de Estado en UI**: Solucionada la desincronización de estado ("Tor activo" / verde) al reabrir la app o navegar entre pestañas.
  - **Hilo UI Seguro**: Corrección del error `NetworkOnMainThreadException` en las pruebas de conectividad de sockets local.
  - **Kill-Switch con Tiempo de Gracia**: Retardo inteligente de 1.2s (3 reintentos) durante el arranque de Tor antes de bloquear conexiones por seguridad.
- **🎮 Experiencia Android TV (D-Pad)**:
  - Navegación fluida con control remoto mediante resaltado dinámico azul neón (`#38BDF8`) en tarjetas Material.
  - Transferencia de foco de control remoto al cambiar de pestaña a la sección Tor.

---

## ✨ Key Features

- **📱 Multi-Platform**: Enjoy a seamless experience on both your Smart TV with remote control and your Smartphone with touch screen.
- **🎮 Dual Apps**: Have two accounts? Vortex One allows you to clone applications to use multiple sessions at the same time.
- **📥 Smart Installer**: Install APK files directly from your USB or internal storage.
- **🛡️ Secure System**: Applications run in an isolated virtual environment (Sandbox), keeping your main system clean.
- **📺 Designed for TV and Mobile**: Intuitive and user-friendly interface, whether using D-Pad or touch interactions.
- **⚡ Super Lightweight**: Optimized to run fast on any device.

---

## 🚀 How to Use

1. **Open the App**: Launch Vortex One from your device menu.
2. **Install Apps**:
   - Use the **"Install APK"** button to browse `.apk` files on your device.
   - Or use **"System Apps"** to clone applications you already have installed.
3. **Run**: Your cloned apps will appear on the main screen. Simply select them to launch.
4. **Manage**:
   - **On TV**: Long press the select button (OK) on an app.
   - **On Mobile**: Long press on the app icon.

> [!NOTE]
> **Important Notice**: Some applications may require full Google Play Services, which may have limitations in the virtual environment depending on your device.

---

## 👨‍💻 Developer Information

If you are a developer, contributor, or just curious about how Vortex One works under the hood (Technologies, Architecture, BlackBox Integration), check out our technical documentation:

👉 **[Read Developer Documentation (DEVELOPER.md)](DEVELOPER.md)**

---

## 📞 Support

If you need help or have suggestions, contact us:
- **GitHub**: [editech-dev](https://github.com/editech-dev)

---

## 🙏 Acknowledgments

Vortex One is built upon the incredible work of the open-source community:

- **[BlackBox](https://github.com/FBlackBox/BlackBox)** — Virtual engine by **ALEX502** (Apache 2.0). The core virtualization that makes app cloning possible.
- **VirtualApp / VirtualAPK** — Original virtualization framework
- **Dobby** — Native inline hook framework
- **xDL** — Enhanced Android dynamic linker
- **BlackReflection / FreeReflection** — Java reflection utilities

See the [NOTICE](NOTICE) file for complete attribution details.

---
*Made with ❤️ for the Android community.*

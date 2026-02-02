# Vortex TV

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Vortex TV** (formerly OpenContainer-TV) is a lightweight, native virtual machine manager for Android TV devices, allowing you to run applications in a virtualized environment using the BlackBox engine.

## 🎯 Características

- ✅ **Virtualización de Apps**: Ejecuta múltiples instancias de la misma app sin conflictos
- ✅ **Optimizado para TV**: Interfaz diseñada para navegación con control remoto (D-Pad)
- ✅ **Sin Root**: No requiere permisos de root
- ✅ **Instalación Manual de APKs**: Escanea y instala APKs desde almacenamiento local
- ✅ **Gestión Completa**: Lanza, pause y desinstala apps virtuales
- ✅ **Lightweight**: Optimizado para dispositivos con 1GB RAM

## 📱 Dispositivos Compatibles

- Amazon Fire TV Stick (todas las generaciones)
- Google TV / Chromecast
- NVIDIA Shield TV
- Xiaomi Mi Box / Mi TV
- Cualquier dispositivo Android TV con API 21+

## 🛠️ Stack Tecnológico

- **Lenguaje**: Kotlin 100%
- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 34
- **UI**: XML con ViewBinding (sin Jetpack Compose para mayor rendimiento)
- **Motor de Virtualización**: BlackBox
- **Arquitecturas**: ARM64-v8a, ARMeabi-v7a

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────┐
│          MainActivity (Dashboard)        │
│  ┌─────────────────────────────────────┐│
│  │  RecyclerView (Grid 4 columnas)     ││
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐        ││
│  │  │App1│ │App2│ │App3│ │App4│        ││
│  │  └────┘ └────┘ └────┘ └────┘        ││
│  └─────────────────────────────────────┘│
│          ▼                    ▼          │
│    FileScannerActivity   VirtualApp Mgr  │
└─────────┬───────────────────┬────────────┘
          │                   │
          ▼                   ▼
    ┌──────────┐      ┌──────────────┐
    │APK Scanner│      │ BlackBox Core│
    └──────────┘      └──────────────┘
```

## 🚀 Instalación

### Opción 1: Descargar APK (Próximamente)
```bash
# Instalar en Fire TV vía ADB
adb connect <IP_FIRE_TV>
adb install app-debug.apk
```

### Opción 2: Compilar desde código fuente

1. **Clonar el repositorio**
```bash
git clone https://github.com/editech-dev/OpenContainer-TV.git
cd OpenContainer-TV
```

2. **Integrar BlackBox** (Ver sección [Integración de BlackBox](#-integración-de-blackbox))

3. **Compilar**
```bash
./gradlew assembleDebug
```

4. **Instalar APK**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## ⚙️ Integración de BlackBox

> [!IMPORTANT]
> El proyecto actualmente usa un stub temporal de BlackBox para permitir la compilación. Para funcionalidad completa, debes integrar la librería BlackBox real.

### Pasos:

1. **Descargar BlackBox**
```bash
git clone https://github.com/FBlackBox/BlackBox.git
```

2. **Opción A: Agregar como módulo**
```bash
cp -r BlackBox/Bcore OpenContainer-TV/
```

Actualizar `settings.gradle.kts`:
```kotlin
include(":app", ":Bcore")
```

3. **Opción B: Usar AAR precompilado**
- Compilar BlackBox y copiar el AAR a `app/libs/blackbox.aar`

4. **Descomentar dependencia en `build.gradle.kts`**
```kotlin
// Línea 80-83
implementation("com.github.FBlackBox:BlackBox:0.6.0")
```

5. **Eliminar stub y descomentar imports**
```bash
rm app/src/main/java/com/editech/services/blackbox/BlackBoxStub.kt
```

Ver instrucciones detalladas en: [`BlackBoxStub.kt`](app/src/main/java/com/editech/services/blackbox/BlackBoxStub.kt)

## 📖 Uso

1. **Launch OpenContainer-TV** desde el launcher de tu TV
2. **Presiona "+ Instalar APK"** con el control remoto
3. **Navega** por la lista de APKs encontrados
4. **Selecciona** el APK a instalar
5. **Espera** a que se complete la instalación
6. **Lanza** la app desde el dashboard principal

### Controles

- **D-Pad**: Navegar entre apps
- **Enter/OK**: Lanzar app seleccionada
- **Long Press (mantener OK)**: Desinstalar app

## 🔧 Desarrollo

### Estructura del Proyecto

```
app/src/main/
├── java/com/editech/services/
│   ├── App.kt                     # Application class
│   ├── MainActivity.kt            # Dashboard
│   ├── activities/
│   │   └── FileScannerActivity.kt # Escáner APK
│   ├── adapters/
│   │   ├── VirtualAppsAdapter.kt  # Grid de apps
│   │   └── ApkFileAdapter.kt      # Lista de APKs
│   ├── models/
│   │   ├── VirtualApp.kt
│   │   └── ApkFile.kt
│   └── blackbox/
│       └── BlackBoxStub.kt        # Stub temporal
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   ├── activity_file_scanner.xml
    │   ├── item_virtual_app.xml
    │   └── item_apk_file.xml
    └── drawable/
        ├── tv_banner.xml
        └── selector_item_virtual_app.xml
```

### Compilar variantes

```bash
# Debug (con logs)
./gradlew assembleDebug

# Release (ofuscado)
./gradlew assembleRelease

# Todas las variantes
./gradlew assemble
```

## 🎨 Personalización

### Cambiar número de columnas en grilla
```kotlin
// MainActivity.kt - línea 56
GridLayoutManager(this@MainActivity, 4) // Cambiar 4 por el número deseado
```

### Cambiar tema de colores
Editar `res/layout/activity_main.xml`:
```xml
android:background="#0F172A" <!-- Tu color hexadecimal -->
```

### Modificar selector de foco
Editar `res/drawable/selector_item_virtual_app.xml`

## 🐛 Problemas Conocidos

- ⚠️ **BlackBox no integrado**: El stub actual no virtualiza apps reales
- ⚠️ **Permisos en Android 11+**: Se requiere solicitar permisos de almacenamiento en runtime
- ⚠️ **Fire OS limitaciones**: Algunas rutas de almacenamiento pueden no ser accesibles

## 🤝 Contribuir

Las contribuciones son bienvenidas! Por favor:

1. Fork el proyecto
2. Crea una rama (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## 📄 Licencia

Este proyecto está bajo la licencia MIT. Ver [`LICENSE`](LICENSE) para más información.

## 🙏 Créditos

- **Motor de Virtualización**: [BlackBox](https://github.com/FBlackBox/BlackBox) by FBlackBox
- **Diseño UI**: Inspirado en mejores prácticas de Android TV

## 📞 Contacto

Edison - [@editech-dev](https://github.com/editech-dev)

Project Link: [https://github.com/editech-dev/OpenContainer-TV](https://github.com/editech-dev/OpenContainer-TV)

---

⭐ Si este proyecto te fue útil, considera darle una estrella!

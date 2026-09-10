plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import com.android.build.api.variant.FilterConfiguration
import java.util.Properties
import java.io.FileInputStream

// Fuente única de la versión: defaultConfig y el nombrado de APKs la comparten.
val appVersionName = "2.0.3"
val appVersionCode = 203

android {
    // IDENTIDAD DE INSTALACIÓN — NO CAMBIAR.
    // `namespace` y `applicationId` valen "com.editech.services" por motivos históricos
    // (el proyecto se llamaba "MediaService"). El nombre comercial "Vortex One" vive en
    // `app_name` (res/values/strings.xml) y en el tema, no aquí.
    // Cambiar el applicationId hace que Android trate el APK como app nueva: el onn TV
    // instalado perdería el acceso a los datos del motor de virtualización
    // (engine/Bcore/.../core/env/BEnvironment.java deriva /data/data/<applicationId>/blackbox
    // y /sdcard/Android/data/<applicationId>/files/blackbox del package del host).
    // No existe migración para eso.
    namespace = "com.editech.services"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.editech.services" // ver aviso sobre IDENTIDAD DE INSTALACIÓN arriba
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Configuración de ABIs nativas para BlackBox
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keystorePropertiesFile))
                
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    // Splits para optimizar tamaño de APK por arquitectura
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
        languageVersion = "1.9"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Nombrado determinista de los APKs de release: VortexOne-vX.Y.Z-<abi>.apk
// (armeabi-v7a, arm64-v8a, universal — según el bloque splits.abi de arriba).
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            output.outputFileName.set("VortexOne-v$appVersionName-$abi.apk")
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    
    // Android TV Leanback
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    
    // Virtualization Engine (based on BlackBox - Apache 2.0, see NOTICE)
    implementation(project(":engine:Bcore"))
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Unity Ads
    implementation("com.unity3d.ads:unity-ads:4.12.0")

    // Room Database
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    annotationProcessor("androidx.room:room-compiler:$room_version")

    // Tor embebido (Guardian Project — Apache 2.0)
    implementation("info.guardianproject:tor-android:0.4.9.11")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
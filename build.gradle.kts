// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Configure extra properties for BlackBox modules
ext {
    set("compileSdkVersion", 35)
    set("targetSdk Version", 28)
    set("minSdk", 21)
    set("versionCode", 311)
    set("versionName", "3.1.1-beta")
}
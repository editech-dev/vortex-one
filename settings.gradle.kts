pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Guardian Project — tor-android embedded library
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "VortexTV"
include(":app")
 
// Virtualization Engine Modules (based on BlackBox - Apache 2.0)
include(":engine:Bcore")
include(":engine:black-reflection")
include(":engine:compiler")

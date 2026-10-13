rootProject.name = "pear"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared", ":desktopApp")
// Phase 7: include(":androidApp") and enable the android target in :shared (see shared/build.gradle.kts).

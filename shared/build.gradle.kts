plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("desktop")

    // Phase 7: add the Android Gradle library plugin to the version catalog and root build, then
    //   androidTarget()
    // and put Android-only code in src/androidMain. Left out now so desktop CI needs no Android SDK.

    sourceSets {
        // Haze's glass module is gated behind @ExperimentalHazeApi (see docs/desktop/glass.md). A marker that does
        // not exist only produces a warning, so this is safe if a later Haze version removes the gate.
        all { languageSettings.optIn("dev.chrisbanes.haze.ExperimentalHazeApi") }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.animation)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.haze)
            implementation(libs.haze.blur)
            implementation(libs.haze.glass)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.ktor.client.okhttp)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

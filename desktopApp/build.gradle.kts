plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.mediahub.pear.desktop.MainKt"
        nativeDistributions {
            packageName = "Pear"
            packageVersion = "1.0.0" // packaging placeholder; the real scheme is decided in Phase 6
            includeAllModules = true
        }
    }
}

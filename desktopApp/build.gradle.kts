plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

val packagingResources = layout.projectDirectory.dir("packaging/resources")

compose.desktop {
    application {
        mainClass = "com.mediahub.pear.desktop.MainKt"
        nativeDistributions {
            packageName = "Pear"
            packageVersion = "1.0.0" // packaging placeholder; the real scheme is decided in Phase 6
            includeAllModules = true
            // libmpv is bundled: each platform's folder is copied into the app's resources, where the player looks first.
            appResourcesRootDir.set(packagingResources)
        }
    }
}

// Fails the packaging early, with the reason, when the libmpv for the OS being packaged isn't in place.
// Set -Ppear.skipLibmpv=true to skip the check (CI builds that only compile/package a shell without the binary).
val checkLibmpv by tasks.registering {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val folder = packagingResources
    val skip = providers.gradleProperty("pear.skipLibmpv").isPresent
    onlyIf { !skip }
    doLast {
        if (skip) return@doLast
        val (sub, file) = when {
            "win" in os -> "windows-x64" to "libmpv-2.dll"
            "linux" in os -> "linux-x64" to "libmpv.so.2"
            else -> return@doLast
        }
        val lib = folder.dir(sub).file(file).asFile
        check(lib.isFile) {
            "libmpv is not bundled: $lib is missing. Put the 64-bit libmpv for $sub there " +
                "(see desktopApp/packaging/resources/README.md) and build again."
        }
    }
}

tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("package") }.configureEach {
    dependsOn(checkLibmpv)
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

// NOTE: the RPM's libmpv requirement is added by the "Rebuild the RPM" step in .github/workflows/desktop-ci.yml, not here.
// The version the app shows is pearVersion (gradle.properties; CI overrides it from the git tag). The installers need a
// numeric version: Windows' version resource and RPM accept only numbers. So the packages get MAJOR.MINOR.PATCH, and
// the "-Alpha" part stays in the app and in the installer's display text.
val pearVersion: String = providers.gradleProperty("pearVersion").get()
val pearNumericVersion: String = Regex("""^\d+\.\d+\.\d+""").find(pearVersion)?.value
    ?: throw GradleException("pearVersion must start with MAJOR.MINOR.PATCH (for example 0.0.1-Alpha), got '$pearVersion'.")

// The icon: one .ico for the exe, the installer and the shortcuts (Windows), and one .png for the window and the Linux
// launcher. Both come from tools/make_icons.py. Without them the build still works, with Java's default icon.
val iconIco = file("packaging/icons/pear.ico")
val iconPng = file("src/main/resources/pear.png")
if (!iconIco.isFile || !iconPng.isFile) {
    logger.warn("Pear's icon is not in the build ($iconIco, $iconPng). Run tools/make_icons.py to add it.")
}

compose.desktop {
    application {
        mainClass = "com.mediahub.pear.desktop.MainKt"
        // An AppCDS archive, written on the first run and read after that (JDK 19 or newer; CI builds with 21). It cuts the
        // class-loading stalls at launch and at the player's first open. The JVM carries on without it when the file cannot
        // be written. The guard flag lets an older JVM ignore these options instead of refusing to start.
        jvmArgs += listOf(
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-Xshare:auto",
            "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=\$APPDIR/pear.jsa",
        )
        nativeDistributions {
            packageName = "Pear"
            packageVersion = pearNumericVersion
            includeAllModules = true // a jlink module list is the next size cut (task 8)

            // Only the Rust P2P engine (p2p_core) is bundled from here. libmpv is not: Windows gets it from the
            // installer, into %LOCALAPPDATA%\Pear\native, and the Linux RPM requires the distribution's mpv-libs.
            appResourcesRootDir.set(packagingResources)

            // Linux ships as an RPM (run :desktopApp:packageRpm). Windows ships as the app image, which the Inno Setup
            // script wraps (see desktopApp/installer/pear.iss), so it needs no target format of its own.
            targetFormats(TargetFormat.Rpm)

            windows {
                if (iconIco.isFile) iconFile.set(iconIco)
            }

            linux {
                packageName = "pear"
                appCategory = "AudioVideo"
                // Becomes Categories= in the .desktop file. It needs a main category (AudioVideo) or menus hide the app.
                menuGroup = "AudioVideo;Video;"
                // Without this jpackage writes no .desktop file: the RPM installs but the app is in no menu.
                shortcut = true
                rpmPackageVersion = pearNumericVersion
                if (iconPng.isFile) iconFile.set(iconPng)
            }
        }
    }
}

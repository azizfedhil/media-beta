import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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

// The version the app shows is pearVersion (set by CI from the git tag; 0.0.1-Alpha by default). The installers need a
// numeric version: Windows' version resource and RPM accept only numbers. So the packages get MAJOR.MINOR.PATCH, and
// the "-Alpha" part stays in the app and in the installer's display text.
val pearVersion: String = providers.gradleProperty("pearVersion").orNull ?: "0.0.1-Alpha"
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
                menuGroup = "Video"
                rpmPackageVersion = pearNumericVersion
                if (iconPng.isFile) iconFile.set(iconPng)
            }
        }
    }
}

// The RPM must require libmpv. Pear loads it with dlopen (JNA), so rpmbuild's automatic ELF dependency scan never sees it
// and the package would install without it. Compose has no DSL option for RPM Requires, so pass jpackage's own flag.
// The soname capability works on every RPM distribution (Fedora's mpv-libs from RPM Fusion, openSUSE's libmpv2, ...).
// This runs in afterEvaluate so it comes after the Compose plugin has configured the task (it may set freeArgs itself),
// and the task logs what it passes, so a build log shows whether the flag reached jpackage.
// Check the result with: rpm -qpR desktopApp/build/compose/binaries/main/rpm/*.rpm
afterEvaluate {
    tasks.named("packageRpm", AbstractJPackageTask::class.java).configure {
        freeArgs.addAll("--linux-package-deps", "libmpv.so.2()(64bit)")
        doFirst { logger.lifecycle("packageRpm: jpackage free args = ${freeArgs.get()}") }
    }
}

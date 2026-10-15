plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.mediahub.pear.android"
    compileSdk = 37

    defaultConfig {
        // Same as the iOS bundle id. Open question 1 in docs/android/decisions.md: confirm before the first release.
        applicationId = "com.mediahub.pear"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = (project.findProperty("pearVersion") as String?) ?: "0.0.2-Alpha"
    }

    buildFeatures {
        compose = true
    }

    // Release signing from the environment (CI decodes the keystore into a file and sets these). Without a keystore the
    // release APK is unsigned.
    val releaseKeystore = System.getenv("PEAR_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PEAR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PEAR_KEY_ALIAS")
                keyPassword = System.getenv("PEAR_KEY_PASSWORD") ?: System.getenv("PEAR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}

// The P2P engine for the phone (plan P9): the Rust core built for three ABIs into src/main/jniLibs before packaging.
// Needs cargo-ndk and an NDK (ANDROID_NDK_HOME); the hosted CI runner has both. Pass -PskipP2pAndroid to skip it: the app
// then shows torrent sources as unavailable, which is what the app does without the library.
val p2pCoreDir = rootProject.file("p2p-core")
val buildP2pCoreAndroid by tasks.registering(Exec::class) {
    group = "p2p"
    description = "Builds the p2p_core engine for Android (arm64, armv7, x86_64) into src/main/jniLibs."
    onlyIf { !project.hasProperty("skipP2pAndroid") }
    workingDir = p2pCoreDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", file("src/main/jniLibs").absolutePath,
        "rustc", "--release", "--lib", "--crate-type", "cdylib",
    )
    inputs.dir(File(p2pCoreDir, "src"))
    inputs.file(File(p2pCoreDir, "Cargo.toml"))
    outputs.dir(file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(buildP2pCoreAndroid) }

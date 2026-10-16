# Android port: decisions and facts

Living log. Every fact here was checked against a source listed next to it. Anything not checked is marked VERIFY.

## 0. Change of plan (2026-10-15)

- **The Android engine is libmpv**, the same engine family as desktop. Media3 is not built. It stays as a named backend
  (`AndroidPlayerBackend.Media3`), so it can be added later without changing the player UI.
- The brief said both "use libmpv" and "just use media3 as video engine". This follows the explicit change of plan
  (libmpv). If Media3 was meant, say so and the seam is ready for it.
- Consequences: the engine needs **no `commonMain` edit**. Plan item E7 (EngineRules for Media3) is dropped. E1 is
  narrowed to the HDR switch only, because libmpv already has audio delay and subtitle delay. No Media3 dependency is
  in the build.

## 1. Verified facts

- **libmpv for Android** is `dev.jdtech.mpv:libmpv:1.0.0` on Maven Central: an AAR built from
  jarnedemeulemeester/libmpv-android v1.0.0. It contains libmpv 0.41, ffmpeg 8.1, libass, libplacebo and mbedtls, for
  4 ABIs. minSdk 26; the library itself compiles against compileSdk 36. Source: central.sonatype.com and the upstream
  `libmpv/build.gradle.kts` (raw.githubusercontent.com, fetched 2026-10-15).
- **The wrapper is JNI, not JNA.** `dev.jdtech.mpv.MPVLib` (MIT) loads `libmpv.so` and `libplayer.so`. The desktop
  binding (JNA, `LibMpv.kt`) cannot be reused as it is. Source: upstream `MPVLib.kt`.
- **Surface.** `MPVLib.attachSurface(Surface)` sets mpv option `wid` to the Surface; `detachSurface()` sets it back to 0.
  Lifecycle, as mpv-android does it (upstream `BaseMPVView.kt`): surfaceCreated → attach and `force-window=yes`;
  surfaceChanged → `android-surface-size`; surfaceDestroyed → `vo=null`, `force-window=no`, detach.
- **END_FILE carries no reason or error code** through the wrapper (upstream `event.cpp` sends only the event id). The
  error text therefore comes from the last error-level log line of the file (`MpvErrorText`). Log lines reach Kotlin
  through `addLogObserver` (upstream `nativeCreate` requests `"v"` logs).
- **Options.** `gpu-context=android`, `opengl-es=yes`, `hwdec-codecs=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1` and
  `ao=audiotrack,opensles` are the mpv-android set (upstream `MPVView.kt`).
- **Hardware decoding.** mpv manual (`DOCS/man/options.rst`): `mediacodec` needs `--vo=gpu --gpu-context=android` and
  decodes zero-copy; `mediacodec-copy` decodes in hardware and copies frames back to RAM. The engine sets
  `hwdec=mediacodec,mediacodec-copy` (a priority list). The decoder in use is read from `hwdec-current`.
- **AGP.** AGP 9.0.1 is documented (developer.android.com release notes, January 2026). A KMP module uses the plugin
  `com.android.kotlin.multiplatform.library` with `kotlin { androidLibrary { ... } }`. The old `com.android.library`
  is not compatible with the KMP plugin in AGP 9. AGP 9 requires KGP 2.2.10 at least.
  VERIFY the newest 9.x for Kotlin 2.4.20 before the first Gradle run.

## 2. Environment limits (affect every task)

- The sandbox cannot reach Maven Central, Google Maven, the Gradle distribution host or the Android SDK host
  (repo1.maven.org, maven.google.com, services.gradle.org and dl.google.com all failed). Also missing: Gradle, cargo,
  the Android SDK and NDK, an emulator. The machine has 1 CPU and 3 GB RAM.
- **So no Gradle task has been run**, desktop or Android. The Kotlin in this batch was type-checked with `kotlinc`
  2.4.20 (downloaded from GitHub releases), against stub Android and coroutines classes plus the real `MPVLib.kt` and the
  real commonMain player types. That catches syntax and type errors in the engine. It does not check Compose code or
  Gradle wiring.
- Until the user runs `gradle :shared:compileKotlinDesktop` and `:androidApp:assembleDebug`, and a device pass, every
  Android file is WRITTEN-NOT-COMPILED-IN-GRADLE and NEEDS-DEVICE.

## 3. Decisions and their status

- **D1 Gradle layout.** Plugin `com.android.kotlin.multiplatform.library`, AGP 9.0.1, in the catalog. Switched on in
  batch 2 together with the platform actuals. Until then the Android target is off, so desktop CI does not see a
  half-built Android target. (Reason: `:shared:build` in CI would otherwise try to compile Android.)
- **D2 SDK levels.** minSdk 26 (libmpv floor, verified). compileSdk and targetSdk 36 (the value libmpv builds against).
- **D3 Media3.** Removed from the active build. Kept as the backend seam `AndroidPlayerBackend.Media3`.
- **D4 Shared JVM code.** Still planned for batch 2 (file storage, secrets, downloads). The hierarchy DSL names are
  not verified, so use a manual `jvmCommonMain` with `dependsOn`, not `withAndroidTarget()`.
- **D5 Video surface.** SurfaceView (kept). libmpv draws into a Surface, and mpv-android uses a SurfaceView. The
  consequence stands: glass blur cannot sample video, so glass over video is flat on Android. A TextureView is possible
  but would cost performance, and the change would need a device test.
- **Dated note, 2026-10-16 (T3, FIX_PLAN.md):** D5 is reversed for the Liquid Glass path only. With the Liquid Glass choice on
  and battery saver off, the picture is a TextureView, so glass can blur it. Frosted, the default, keeps the SurfaceView, and
  so does battery saver. The TextureView costs more battery. The device checks that decide T3 are in FIX_PLAN.md (T3, step 5).
  Until they pass, the decision stays open.
- **D6 No foreground service, no Now-Playing in v1.** Kept.
- **D7 P2P last and optional.** Kept. The app must run with P2P reporting Unavailable.
- **D8 Cleartext HTTP allowed.** Kept (manifest in batch 2). Add-on and P2P links are plain HTTP.
- **D9 Codecs.** Changed. libmpv carries its own ffmpeg, so the old Media3 codec gap (AC-3, E-AC-3, DTS, TrueHD, software
  AV1/VP9) may not exist here. VERIFY on device with the stream matrix before deciding anything. The ffmpeg build's
  licence (LGPL/GPL options) is still a decision for the user before shipping.
- **D10 Out of scope for v1.** Kept.
- **D11 Backup.** `allowBackup=false` (batch 2). Kept.
- **D12 Verification = compile, not tests.** Kept. Stub checks are compile checks, not tests. No test was written or run.
- **D13 (new) Engine.** libmpv on Android via the AAR's JNI wrapper. Video through a Surface, hardware decoding through
  MediaCodec. Media3 is a seam, not a build dependency.
- **D14 (new) Error text.** Read from mpv's last error line for the file (see section 1).
- **D15 (new) CA bundle.** https sources need a CA bundle, or mpv cannot verify them. Batch 2 adds `cacert.pem` from
  the mpv-android repository's assets (VERIFY its licence line and version) and copies it into filesDir. The engine only
  sets `tls-ca-file` and `tls-verify=yes` when the file exists.

## 4. Known gaps (v1)

- **HDR on/off switch does nothing on Android.** The GPU renderer tone-maps HDR to SDR by default. The right mpv option
  for turning that off is not verified. Verify on device, then hide the switch (E1).
- **Performance mode** caps the rendered size at 1080p (mpv scales the picture). Verify the effect on a 4K source.
- **Error wording** is mpv's log text as expected (HTTP status, "failed to recognize file format", and so on). Verify on
  device, especially the 401/403/404/5xx and network cases.
- **Double-tap on the SurfaceView.** The Compose `pointerInput` sits over an AndroidView. Verify the double-tap reaches it.
- **Glass over video** is flat (D5). Intended.
- **Surface re-creation** (rotation, PiP, fullscreen) re-attaches the surface. Verify no restart and no black frame.

## 5. Batch 2 status (what changed since section 3)

- **D1 done.** `:shared` applies `com.android.kotlin.multiplatform.library` and declares `androidLibrary { namespace; compileSdk = 36; minSdk = 26 }`. The DSL is the one in the official Android KMP page. The Android target is on, so Gradle now configures AGP for `:shared` (see BLOCKERS B2).
- **D2 done.** minSdk 26, compileSdk 36.
- **D4 done, by manual wiring.** `jvmCommonMain` is declared with `dependsOn(commonMain)`, and `desktopMain` and `androidMain` each `dependsOn(jvmCommonMain)`. This avoids the hierarchy DSL names, which were not verified. Moved: FilePrefsPersistence, FileFactStore, FileResponseCache, the PlistCodec actual (renamed `PlistCodec.jvm.kt`), and the folder store and HTTP transport from DesktopDownloads.kt (now `JvmDownloads.kt`; desktop keeps its two download actuals in `DesktopDownloads.kt`). The dd-plist dependency moved with them.
- **D8 done.** `usesCleartextTraffic=true` in the manifest.
- **D11 done.** `allowBackup=false`.
- **D15 done.** `androidApp/src/main/assets/cacert.pem` is the Mozilla CA bundle as curl extracts it (121 roots, header dated 13 Aug 2026), taken from `mpv-android/app/src/main/assets`. PearApplication copies it to filesDir when it is missing or changed. VERIFY the licence line for this file before release (Mozilla CA data is MPL 2.0; the mpv-android code is MIT).
- **Activity.** `androidx.activity:activity-compose:1.13.0`, the latest stable release per developer.android.com (1.14 is alpha).
- **E4 done.** `SystemBackHandler` is an expect in commonMain, called once in `App()` with `env.shell.escape()`. Desktop: no-op. Android: `BackHandler`, which finishes the activity when the shell has nothing to close.
- **Keep-awake.** Android sets `FLAG_KEEP_SCREEN_ON` on the activity window while the player is awake (KeepAwakeRules, unchanged).
- **Power.** `PowerStatus.lowBattery` follows the system battery saver. This is an approximation: the desktop value is "20% or less, not charging". Glass falls back to flat while battery saver is on.
- **Insets.** The whole app is padded by the safe area, except in fullscreen. This is v1's simple form (plan T3.5). It means screens do not extend under the status bar as they do on iOS.
- **Mouse.** Right-click on Android is the mouse's secondary button (MotionEvent). Touch never counts.
- **Icon.** The 512 px desktop PNG is used as the launcher icon (`mipmap-xxxhdpi`). It is a placeholder: an adaptive icon is a later polish task.
- **Fonts.** The system font on Android (no Inter bundle yet).
- **Downloads folder.** `getExternalFilesDir("downloads")`, which is removed with the app and needs no permission. Fallback: the config folder.
- **P2P.** Android has no engine yet. Asking for a stream returns `P2PFailure.Unavailable`, and the Wi-Fi-only setting is not enforced until E10 and P9.
- **Known gaps added.** Downloads run only while the process is alive (plan D10, unchanged). Keystore behaviour depends on the device (the fallback file is used when the key cannot be made). A hardware back key and the back gesture both reach the shell through the same system back action.

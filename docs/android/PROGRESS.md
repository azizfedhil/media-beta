# Android port: progress

Status key: DONE (checked as far as this sandbox allows) | WRITTEN (not compiled by Gradle) | STUB-CHECKED (compiled with
kotlinc against stubs of the Android types it uses) | NEEDS-DEVICE | BLOCKED (see BLOCKERS.md) | TODO | TEST-SKIPPED
(rule 10: no tests written or run).

## Batch 1: libmpv engine and surface (delivered)

- [x] P0.1 Toolchain recorded: Java 21 present; no gradle, cargo, Android SDK or NDK in the sandbox.
- [x] P0.3 Versions checked: AGP 9.0.1, minSdk 26 (libmpv floor), compile/target 36.
- [x] P4 libmpv engine: AndroidMpvEngine, MpvErrorText, AndroidPlayers (backend seam; Media3 reserved), PlayerEngine.android. STUB-CHECKED. NEEDS-DEVICE.
- [x] P5 VideoSurface.android: SurfaceView with attach, size and detach. WRITTEN (Compose not stub-checked). NEEDS-DEVICE.

## Batch 2: Gradle target, platform actuals, app entry (this delivery)

- [x] P1.2 Root build: androidKotlinMultiplatformLibrary and androidApplication with apply false. WRITTEN.
- [x] P1.3 shared: androidLibrary (namespace com.mediahub.pear.shared, compileSdk 36, minSdk 26), androidMain dependencies (ktor okhttp, coroutines-android, libmpv AAR, activity-compose 1.13.0). WRITTEN. DSL checked against developer.android.com.
- [x] P1.4 jvmCommonMain (D4): FilePrefsPersistence, FileFactStore, FileResponseCache, PlistCodec (renamed .jvm.kt), FolderDownloadFiles, HttpDownloadTransport and the user agent (JvmDownloads.kt); dd-plist moved to jvmCommonMain. Desktop keeps its two download actuals. WRITTEN (desktop compile not run).
- [x] P1.5 androidApp module, included in settings. WRITTEN.
- [x] P2.1 AndroidHost (app and activity). STUB-CHECKED.
- [x] P2.2 Actuals: Clock, Platform, ImageLoader, PearType (system font), BackButton (false), Clicks (mouse secondary button), HiddenCursor, KeepAwake (FLAG_KEEP_SCREEN_ON), SystemMediaSession (none), P2P platform stub, P2P network (always allowed), blockingUntil, DownloadPlatform. Clock and KeepAwake STUB-CHECKED; others WRITTEN.
- [x] P2.4 AndroidDirs. STUB-CHECKED.
- [x] P2.5 AndroidStorage and AndroidNetwork. WRITTEN.
- [x] P2.6 AndroidSecretStore (Keystore AES-GCM, fallback file). STUB-CHECKED (JDK crypto calls are real; the Keystore itself needs a device).
- [x] P2.7 Downloads: folder under the app's external files dir, fallback to config. WRITTEN.
- [x] P3 App entry: manifest (cleartext on, allowBackup off, configChanges so rotation does not restart the player), PearApplication, MainActivity (edge-to-edge, safe area except fullscreen, power-save receiver, flush on stop, AppExit on finish), AndroidWindowControl (state-backed mode, system bars and landscape for fullscreen), theme, icon placeholder. WRITTEN. NEEDS-DEVICE.
- [x] D15 CA bundle: assets/cacert.pem (121 roots, from mpv-android's assets) copied to filesDir at start. WRITTEN. The licence of the bundle file itself is VERIFY.
- [x] E4 back action: commonMain SystemBackHandler (expect), desktop no-op, Android BackHandler, one call in App(). WRITTEN. Desktop behaviour unchanged.
- [ ] B1 DesktopClipboard in commonMain uses java.awt: BLOCKED, needs approval (BLOCKERS.md).
- [ ] B2 Android SDK for :shared configuration: decision needed (BLOCKERS.md).

## Still to do

- [ ] P6 Player integration (E2 pill PiP, E3 pause on background, E5 blur/touch capability, E6 PiP state, E8 auto-fullscreen, E9 touch gestures) and PiP in MainActivity. Batch 3. Reference: `Pear_ios_app` PlayerView.swift and PlayerPiP.swift (in the uploads).
- [ ] E1 narrowed: hide the HDR switch on Android. Batch 3.
- [ ] P7 Capability and pill rules. Batch 3.
- [ ] P9 P2P on Android (E10 and the engine). Batch 4, optional.
- [ ] P9b Codec decision, now that libmpv carries its own ffmpeg. After the device stream matrix.
- [ ] P10 CI and release (android-ci.yml, signing). After device checks and B2.

## Device and build checks owed (NEEDS-DEVICE / NOT RUN)

- `gradle :shared:compileKotlinDesktop -PlibmpvSkip=true` must still pass (desktop). Not run here. Expected to pass: the moved files keep their packages, and the only desktop-facing commonMain change is the E4 call with its desktop actual.
- `gradle :androidApp:assembleDebug` after B1 and B2: not run here.
- Playback (batch 1 list): H.264 MP4 plays; play, pause, seek, rate, volume; decoderInUse() reports mediacodec or mediacodec-copy; 401/403/404/5xx messages; rotation keeps playing; SurfaceView re-attach.
- Shell: system back closes the player, then pages, then leaves the app; the phone layout appears; the safe area is clear of the status bar; fullscreen hides the bars.
- Storage: sign in to Simkl, kill and relaunch (tokens survive); settings backup export and import.
- Keep-awake: screen stays on while playing and off while paused.
- P2P: asking for a torrent stream shows "Peer-to-peer is not available in this build yet."

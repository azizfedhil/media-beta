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

## Android fix plan (docs/android/FIX_PLAN.md)

- [x] Plan revision 2: corrections checked against the code and the iOS source (`Pear.-main/MediaHub`). Decision labels PD1 to PD4, git fallback, B1 status, T3 decision record, themes-hidden switch (T9-a), background-save note (T8-b.3), T7 touch test. WRITTEN.
- [x] T7 touch long press and touch slop in `clickOrMenu` (`shell/Clicks.kt`). Touch and stylus take a new path (`withTimeoutOrNull` for the long press, `viewConfiguration.touchSlop` and `longPressTimeoutMillis`, haptic, swallow until up). Mouse path logic unchanged. WRITTEN. Not compiled (no Gradle, no Compose jars in the sandbox). Owed: section 4 item 8 (device long press and tap), item 11 (desktop right-click smoke test). Compose names to check first on the first build: `PointerInputScope.viewConfiguration`, `AwaitPointerEventScope`, `HapticFeedbackType.LongPress`.
- Optional polish in T7 (context menu placement below the anchor on touch): not done, as the plan says.
- Note: the iOS source is `Pear.-main`, not `Pear_ios_app` as written above in this file.
- [x] T1 player look rules (`player/ui/PlayerLogic.kt`, `player/ui/PlayerSurfaces.kt`, `shell/PlayerScreen.kt`). `PlayerLook.Frosted` added; `playerLook` takes `videoSampleable` (default true) and returns Frosted before any Blur or Tint when the video cannot be sampled; `PlayerSurface` has a Frosted branch with its own brushed rim. `PlayerScreen` passes `videoSampleable = PlatformCaps.videoBlurSupported`. WRITTEN. Not compiled. Checked with a model of the rule: desktop results unchanged for all 16 inputs; Android with glass on never gets Blur or Tint. No unit test added (decision D12). Owed: section 4 item 3 (Frosted look playing and paused, readable on bright and dark frames), item 11 (desktop smoke test). Note: on Android with the app-wide glass style set to Flat, the player now shows Frosted instead of Tint, as the plan's rule order intends.
- [x] T6 wide layout only on tablets. `PlatformCaps.supportsWideLayout` (desktop true; Android true when the smallest screen width is at least 600 dp, read live). `layoutModeFor`, `OnboardingRules.layoutFor` and the shell call site take `allowWide`; a phone is always the phone layout. The Settings "Layout override" block (with its divider) is shown only where `supportsWideLayout` is true. Other wide decisions (detail, home, cards) read `LocalWideLayout`, which the shell sets from `layoutModeFor`, so they follow. WRITTEN. Not compiled. Checked with a model of the rule: desktop unchanged; a phone is never Expanded, whatever the override. Existing LayoutModeTest and OnboardingRulesTest still compile through the default arguments; not run (decision D12). Owed: section 4 items 1 and 2 (phone in portrait and landscape; a tablet or an emulator at 600 dp or wider), item 11 (desktop smoke test).
- [x] T5 player margins and insets. `PlayerInsets`, `PlayerMargins` and `playerMargins` in `PlayerLogic.kt` (touch minimums and inset offsets as named constants, insets divided by scale). `LocalPlayerInsets` (public) and `LocalPlayerMargins` in `PlayerOverlay.kt`; the overlay computes the margins from the metrics, the insets and `PlatformCaps.isTouchFirst`. Controls, notice, panels, skip and subtitles read the margins. `MainActivity` provides the union of display cutout, bars (ignoring visibility) and gestures, in dp, only in full screen. WRITTEN. Not compiled. Checked with a model: desktop identical at every size; touch margins clear every inset and minimum. Corrections to the plan are in Revision 3 of FIX_PLAN.md (subtitle lift, side panels, skip button). The `WindowInsets` members and `union` are `// VERIFY`. The old `PlayerMetrics.sideMargin` and `bottomMargin` helpers are now unused (warnings only; kept as the plan asks). Owed: section 4 item 4 (margins clear the notch, corners and gesture bar in landscape full screen, panels and skip follow), item 11 (desktop smoke test).

## Verification before T9 (T7, T1, T6, T5 checked against FIX_PLAN revisions 2 and 3)

- [x] Code read against the plan for T7, T1, T6 and T5. Each matches the revised plan (touch path and slop in `Clicks.kt`; `playerLook` rule order, Frosted branch and `PlayerScreen` call; `supportsWideLayout`, `layoutModeFor(allowWide)` and its three call sites, Settings gate; `playerMargins`, the overlay, panels, skip button, subtitles and `MainActivity` insets). Nothing in them was changed.
- [x] `docs/android/patches/T7.patch`, `T1.patch`, `T6.patch` and `T5.patch` reverse-apply cleanly to the current tree, so the tree is exactly the patched state.
- Still owed, unchanged: every item the entries above list for section 4, the `WindowInsets` imports in `MainActivity` (VERIFY), and the build checks.

## Android fix plan: T9 (Home feed order and MDBList rows)

Each sub-task is its own patch, applied in order: `T9a.patch`, then `T9b.patch`, then `T9c.patch` (no `.git` folder, so the rule is a patch per task). The patches hold code only. Applying the three to a clean copy reproduces this tree exactly.

- [x] T9-a feed order. New `home/HomeFeedOrder.kt` (`FeedSlot`, `feedOrder`), and `HomeScreen.kt` `FeedRows` draws the blocks through it. Themes-hidden switch applied (`BuiltinRows.THEMES`), add-on count taken after hiding, as iOS does. WRITTEN, not compiled. Checked with `docs/android/checks/feed_order_check.py`: a hand port of the Kotlin against a port of iOS `feedRows` and `trailingThemes` agrees on 10,400 input combinations, and the Cinemeta + TMDB + MDBList example in the plan comes out in the acceptance order. The model is a hand port, not the compiler. Desktop feed order changes too, as the T9 note in the plan intends. Owed: section 4 item 10 (feed order), item 11 (desktop smoke test).
- [x] T9-b MDBList rows on Home. `HomeModel.lists` and `loadLists` (port of iOS `loadLists`, with `refiltered` for the unreachable-MDBList case), started in `refresh()`. `HomeScreen` restarts the refresh when `MDBLIST_KEY` or `MDBLIST_LISTS` change, and draws the Lists slot with `CatalogRowView`. WRITTEN, not compiled. Deviation: the per-list fetches use `launch` with a `Mutex`, not `async`, so a row appears when its list arrives (the plan asks for that); the result is the same, in list order. Checked with `docs/android/checks/mdblist_check.py` (20,000 random scenarios against the iOS logic). Owed: section 4 item 10 (MDBList rows appear with lists chosen).
- [x] T9-c MDBList picker in Settings. `settings/IntegrationsScreen.kt`: `MdbListPicker` under the key field, shown when a key is typed. Lists load 700 ms after the key stops changing (one request per pause, not per letter). The choice is written as sorted, comma-separated ids to `MDBLIST_LISTS`, as iOS `MDBListPicker` does. WRITTEN, not compiled. This is in `commonMain`, so the picker also appears on desktop; that is iOS parity under the T9 note, but it is a desktop change. Owed: section 4 items 10 and 11.

Open decisions for the owner:
- Typing an MDBList key refreshes Home on each keystroke, because the plan keys the refresh on `MDBLIST_KEY`. Home stays composed while Settings is open, so this runs in the background. Consider keying on a debounced or saved value, or refreshing only from the picker.
- The picker on desktop (see T9-c).

Next task in plan order: T8 (Continue Watching).

## Android fix plan: T8 (Continue Watching)

One patch for the task: `docs/android/patches/T8.patch`. It applies on top of T9c. The T8-a log lines are in it, marked `// TEMP-CW`.

- [ ] T8-a diagnosis: NEEDS-DEVICE. The `TEMP-CW` lines are in `PlaybackProgress.save`, `PlayerScreen` leave, `WatchHistory.update` and `checkpoint`, and `HomeScreen.FeedRows`. Run the plan's scenarios on this build (film for more than 40 s then back, and then Home; under 30 s; Home button, kill, reopen) and write the outcome here before removing the lines. The plan's STOP AND REPORT conditions apply to that run. The T8-b changes below are in the same build and may change the result, so run it on this build, not the one before.
- [x] T8-b.1 tap target: WRITTEN, not compiled. On touch, the card opens the title's page on that episode (`onSelectTitle` with season and episode). Desktop keeps the source chooser.
- [x] T8-b.2 resume card look: WRITTEN, not compiled. The in-progress card has no UP NEXT badge. It has a time pill (`Fmt.clock`, 11 sp bold, tabular figures, black 62% capsule, 8 dp outer padding), a bottom shade (clear from the middle to black 50%), a 4 dp progress bar (track white 30%, fill the accent gradient), and the subtitle `S · E · time left` with no episode title. Card width 270 dp, spacing 14 dp, row margin 16 dp, on both cards.
  - Kept from the old code, deliberately: the wide-layout scale still multiplies the 270 dp base, so desktop cards are 270 × 1.33 rather than 260 × 1.33.
  - The 16 dp margin is also on the header. The other Home rows keep 20 dp until the home-wide padding is decided (IOS_PARITY, T10), so the Continue row does not line up with them for now.
  - iOS `UpNextCard` also has a bottom shade. The plan does not ask for it, so it is not added. List it in IOS_PARITY.
- [x] T8-b.3 bank on background: WRITTEN, not compiled. `PlaybackProgress.bank` logs the play time, flushes the watch log and does a full save (`isFinal = true`), without closing the session. `PlayerScreen` calls it before `engine.pause()`, then calls `data.storage.flush()`. The reason for the extra flush: `onStop` flushes settings before the `AppLifecycle` collector runs, so without it the banked position would only be in memory. The collector's key is now `(engine, progress)`, so it always banks the current session. Deliberate difference from iOS (`PlayerView.swift:645-646` saves quietly); list it in IOS_PARITY.md (T10).
- [x] T8-b.4 activity restarts and storage: WRITTEN, not compiled. The manifest's `configChanges` now includes density, uiMode, fontScale, locale, layoutDirection and colorMode. `PearApplication.storage` is created in `onCreate` (after `AndroidHost.attach`); `MainActivity` reads it. The plan asked only for storage. `androidNetwork()` is still made per activity. That is a possible follow-up, not done here.
  - Owed because of the new `configChanges`: check that a font-size or locale change updates the screen text without a restart. Locale changes no longer recreate the activity, so any string that is read once would stay stale.

Owed, from the plan's acceptance for T8 (device): play 2 minutes and back out, and the card shows the right still, the pill, the bar and "S1 · E2 · 38 min left"; tap opens the detail page on that episode; long press shows "Remove from Continue Watching" first; remove works; Home button, kill and reopen keeps the card (section 4, item 9).

Next task in plan order: T2 (Android-only player glass setting).

## Android fix plan: T2 (Android-only player glass setting)

One patch: `docs/android/patches/T2.patch`, on top of T8. Check script: `docs/android/checks/t2_look_check.py`.

- [x] T2 player glass choice: WRITTEN, not compiled.
  - `PrefKeys.ANDROID_PLAYER_LIQUID_GLASS` (`android.playerLiquidGlass`, default false), next to the `DESKTOP_*` keys. Not in `BackupRules` (PD4).
  - `PlatformCaps.hasPlayerGlassChoice`: Android true, desktop false. `videoBlurSupported` stays **false** on Android until T3, as the plan says; its doc now states the new meaning ("this platform can blur the video when asked").
  - `PlayerScreen`: `sampleable` is decided once when the player opens: on Android it is `liquid && videoBlurSupported && !PowerStatus.lowBattery`, elsewhere `videoBlurSupported`. On Android `blurWhilePlaying` is true. `sampleable` is not yet passed to `VideoSurface` (that is T3).
  - Settings, Playback: on Android the row reads "Glass controls". Under it, when glass is on, a "Liquid Glass" row with its subtitle and a switch. Turning it on opens "Use Liquid Glass in the player?" with "Keep Frosted" (leaves it off) and "Use Liquid Glass". Turning it off needs no dialog. The desktop "Blur controls while playing" row is hidden on Android.
  - Checked with `docs/android/checks/t2_look_check.py`: on desktop the player look is unchanged over all 64 input cases. On Android the look is unchanged and is Frosted in every case with glass on.
- **Until T3, the Liquid switch has no visible effect on Android.** `sampleable` stays false, so the controls stay Frosted. This is what the plan's order implies. The switch and the warning are in place so they can be checked now, and they start to matter at T3.
- Owed (device): fresh install shows "Glass controls" on and "Liquid Glass" off. Turning Liquid on shows the warning, and "Keep Frosted" leaves it off. Reopening the player applies the setting, and battery saver gives Frosted (section 4, items 5 and 6 are partly this). The dialog copy and the switch layout look right at phone width.

Next task in plan order: T4 (onboarding, which depends on T2).

## Android fix plan: T4 (onboarding, player glass step, Android only)

One patch: `docs/android/patches/T4.patch`, on top of T2. Check script: `docs/android/checks/t4_onboarding_check.py`.

- [x] T4 onboarding player step: WRITTEN, not compiled.
  - `OnboardingStep` has `Player` between `Look` and `Content`. `next()` and `previous()` walk `OnboardingRules.steps`, which leaves `Player` out where `PlatformCaps.hasPlayerGlassChoice` is false. Desktop never sees the step.
  - Title "Player glass", the subtitle from the plan, and the rail label "Player" are in `OnboardingCommon`.
  - `ProgressDots` loops over `OnboardingRules.steps`, as the plan says. **The plan did not name the wide rail in `OnboardingWide.kt`, but it also iterated `OnboardingStep.entries`, so on desktop it would have shown a "Player" item. It now loops over `OnboardingRules.steps` too.**
  - The body (`PlayerGlassBody`): two choice cards in an `OnboardingCard`. "Frosted glass" (line "Soft, light and easy on the battery.", tag "Recommended") is selected by default. "Liquid Glass" (line "Live blur and refraction, like the rest of the app.") always shows the orange warning with the exclamation icon. A tap writes `ANDROID_PLAYER_LIQUID_GLASS` at once, and the selection is read back from the setting, so it survives Back and Next.
  - **Deviation: one column on both frames, with no preview card.** The plan said to use `PreviewCard` beside the choices on the wide frame if that fits. `PreviewCard` shows the accent, not the glass look, so it would mislead here. The body takes no `wide` parameter for that reason.
  - Every exhaustive `when` over the step has a `Player` branch: `stepTitle`, `stepSubtitle`, `railLabel` and the body dispatcher. `primaryLabel` has an `else`. Nothing outside the onboarding package uses the enum.
  - The existing `OnboardingRulesTest` expects the seven desktop steps, which is the sequence the model reproduces. It was not run (decision D12).
- Owed (device): a new install shows the extra step after the colour step, with Frosted selected. Choosing Liquid Glass and finishing turns the Settings switch on (Settings reads the setting when it opens). Back and Next keep the selection. Desktop onboarding is unchanged: seven dots and a seven-item rail.
- As with T2: until T3 lands, choosing Liquid Glass changes nothing visible on Android. The step is in place so it can be checked now.

Next task in plan order: T3 (Liquid Glass surface: TextureView). It is the riskiest task, with a STOP AND REPORT condition. T10 (IOS_PARITY) comes after it.

## Android fix plan: T3 (Liquid Glass surface: TextureView)

One patch: `docs/android/patches/T3.patch`, on top of T4. Check script: `docs/android/checks/t3_look_check.py`.

- [ ] T3 TextureView for the Liquid Glass path: NEEDS-DEVICE. Written, not compiled.
  - `VideoSurface` (expect) has `sampleable: Boolean = false` as its last parameter. The desktop actual takes it and ignores it, because desktop frames are drawn into a Compose canvas. `PlayerCheckScreen` keeps compiling through the default.
  - `PlayerScreen` passes `sampleable = sampleable` (the value from T2).
  - Android actual: `SurfaceVideo` is the SurfaceView path as it was (same callback, same lifecycle). `TextureVideo` is new: a `TextureView` with the plan's listener. It attaches on available, updates the size on change, and on destroyed detaches first, then releases its `Surface`. It clears the listener on release. `key(sampleable)` picks between them, so the Frosted default never reaches the TextureView code.
  - `PlatformCaps.android.videoBlurSupported` is now `true`, as the plan says for this task. With Liquid Glass off, or battery saver on, `sampleable` is false, so the look is Frosted over the SurfaceView, as before.
  - Decision record (plan T3 step 6): a dated note under D5 in `decisions.md`, saying D5 is reversed for the Liquid Glass path only.
  - Checked with `docs/android/checks/t3_look_check.py`: 32 Android cases. The SurfaceView and the Frosted look are used unless Liquid Glass is on with battery OK; then it is the TextureView and Blur. Desktop's look is unchanged.
  - Stale comment in `PlayerLogic.playerLook` now names the Frosted path, not "SurfaceView" alone.
- **STOP AND REPORT (plan T3 step 5) has not been evaluated.** It needs a device. If the blur samples black or transparent, that means Haze is not capturing the TextureView. Stop there. Do not add PixelCopy or screenshots without the owner agreeing.
- Owed (device), all from the plan's step 5: Liquid Glass on, the controls blur the live video, with no rectangles, while paused and while playing. Rotation, fullscreen, PiP and returning from the background bring the picture back, with no black frame and no restart. Seeking and pausing show no tearing. Frosted (default) behaves and draws the same as before this task. If a 4K or HDR file drops frames, report it with the device model, and do not change decoder options.
- Compile names to check first on the first build: `TextureView.SurfaceTextureListener`, `android.graphics.SurfaceTexture`, `android.view.Surface(SurfaceTexture)`, `androidx.compose.runtime.key`, and `AndroidView`'s `onRelease` with a `TextureView`.

Next task in plan order: T10 (IOS_PARITY.md, a document, not code). That is the last task in the plan.

## Android fix plan: T10 (iOS 1:1 audit, a document)

One file, no code: `docs/android/IOS_PARITY.md`. Patch: `docs/android/patches/T10.patch` (docs only).

- [x] T10 iOS parity audit: WRITTEN. Static reading only: not compiled, not run on a device. 95 rows across ten areas: 54 TODO, 5 WRITTEN (covered by T1 to T9), 7 NEEDS-DEVICE, 12 DECIDE, 17 NO CHANGE. Nothing implemented, as the plan asks. The counts are in IOS_PARITY.md section 2.
- Highest-priority TODO rows: HOME-14 (the "Network icons on posters" switch does nothing: Settings writes it, PosterCard never reads it), SET-01 (Android has no file backup, only the clipboard, so the T9 user note cannot be followed as written), DL-01 and DL-02 (Downloads has no Pause All, Resume All, Delete All or free-space line), DETAIL-04 (Play has no download long-press menu, and T7's acceptance list does not name it), EXPLORE-01 and EXPLORE-02 (no TMDB-key or no-results state), PLAYER-05 (the Episodes button is not built), PROF-02 (the Simkl identity flag cannot be set in the profile editor).
- Found while auditing, not acted on:
  - PLAYER-07: picture-in-picture is wired in `MainActivity.kt` (lines 55 to 136), but P6 is unchecked above. Reconcile when P6 is picked up.
  - HOME-26: five `// TEMP-CW` lines remain (`HomeScreen.kt:154`, `PlayerScreen.kt:136`, `PlaybackProgress.kt:185`, `WatchHistory.kt:222` and `:262`). They also print on desktop. Remove them after the T8-a device run.
  - PLAYER-06: the Display button shows on Android, but the enum comment says desktop only, and iOS has no such control.
- Owed, unchanged from earlier tasks: the device checks in section 4 of the plan, and the STOP AND REPORT condition in T3 step 5 (whether Haze samples the TextureView). T3 is recorded here as NEEDS-DEVICE. The owner reports T3 complete; its device checks are not recorded in this file yet.
- Decisions the owner needs before further code work: IOS_PARITY.md section 5 (eleven items).
- Plan order: T10 is the last task in section 3 of the plan. There is no next task in the plan. The next steps are the device checks and the decisions above.

## Android fix plan: revision 4 (parity tasks, planning only)

`docs/android/PARITY_TASKS.md` turns the `TODO`, `DECIDE` and `NEEDS-DEVICE` rows of `IOS_PARITY.md` into patch-sized tasks, PT1 to PT30, with gates and desktop effects. Patch: `docs/android/patches/plan-revision-4-docs.patch`. No code is changed by this revision.

- [x] Plan revision 4 written: WRITTEN. Stage A (PT1 to PT11, PT15, PT30) changes desktop as well as Android, so it waits for decision 14 in section 4 of the file. Until the owner answers that, nothing in Stage A starts.
- [x] IOS_PARITY.md corrected: HOME-22 also has a hard-coded purple (`ContinueRow.kt:277`); DETAIL-04 now describes the state-based iOS menu and the existing `downloadActions` builder; SHELL-05 added (51 colour literals, 39 outside the theme tokens). Totals are now 96 rows: 55 TODO, 5 WRITTEN, 7 NEEDS-DEVICE, 12 DECIDE, 17 NO CHANGE.
- Corrections to earlier notes, found while writing revision 4:
  - Desktop has no backup file route either (no `FileDialog` or file chooser in `desktopMain`). SET-01 therefore affects desktop too, and it is gated by decision 11 and approval (PT19).
  - `FolderDownloadFiles` lives in `jvmCommonMain`, which Android and desktop share. One `usableSpace` call covers the free-space line on both (PT9).
  - The Episodes button can reuse the episode list the player already loads (`PlayerScreen.kt:417`). PLAYER-05 is therefore size L, not larger (PT17).
  - Compose Material is not in the build. Pull-to-refresh needs either a new library or a hand-written gesture, so it is a decision (12, PT27), not a task.
  - Home has no "settled" flag, so the "Nothing to show" rule needs one added (PT15).
- Owed, unchanged: the device checks D1 to D11 in section 3 of the revision, and the device checks from earlier tasks.
- Next: the owner answers decision 14 and the other decisions in section 4 of the revision. Stage A then starts with PT1, one patch at a time.

## CI run 1 (android-ci.yml): compile error fixed

Patch: `docs/android/patches/CI-1.patch` (one file, `androidApp/.../MainActivity.kt`).

- The first CI build of the Android app got as far as `:androidApp:compileDebugKotlin` and failed with one error: `MainActivity.kt:71:37`, "The API of this layout is experimental". Column 37 is `WindowInsets.systemBarsIgnoringVisibility`, the member T5 marked VERIFY. It is the only experimental API in the block; the displayCutout, systemGestures and union calls on the same lines were not reported.
- Fix: `@OptIn(ExperimentalLayoutApi::class)` on `MainActivity.onCreate`, with the import. This follows the pattern `shared/` already uses for the same API (`PearControls.kt`, `ProfileViews.kt`, `StorageCheck.kt`). The VERIFY comment in `MainActivity` is replaced by a note on what the compile showed.
- Status: WRITTEN, not verified by a build here. This sandbox has no Gradle, so the fix is checked only by reading and by the patch round-trip. The next CI run is the test.
- What the log shows: the Android `:shared` tasks ran (`processAndroidMainJavaRes`, `bundleAndroidMainClassesToCompileJar`, `bundleLibRuntimeToDirAndroidMain`), so the Android target of `:shared` configures and compiles in CI. This is option (a) in BLOCKERS B2 (the SDK is installed in CI). B2 is still open until the owner closes it.
- Not in the log, so not checked: `desktop-ci.yml` (desktop compile and packaging), `assembleRelease` (it did not run after the failure), and the device checks.
- If the next run shows a further error in `MainActivity.kt` or another androidApp file, it is a new finding. Report it as CI-2.
- T5's "WindowInsets members VERIFY" note is resolved as described above. The device check for fullscreen margins (T5 acceptance) is still owed.

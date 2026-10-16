# Android fix plan

Audience: Claude Haiku (or any smaller model) doing the work task by task. Target: the Android app should match the iOS app
(`Pear.-main`, the `MediaHub/` folder) 1:1, except where this plan says Android differs on purpose.

Save this file as `docs/android/FIX_PLAN.md` in the repo. Everything below was found by reading the code. Nothing has been
compiled or run on a device.

---

## Revision 2 (corrections after checking against the code and the iOS source)

Checked against `media-beta-main` (Android and shared code) and `Pear.-main` (the iOS source, `MediaHub/`). Every file path and
line number in this plan was checked. These corrections apply to everything below:

- **Decision labels.** The plan's own labels are **PD1 to PD4**. They used to be D1 to D4, which clash with the repo's
  `docs/android/decisions.md` (D1 to D15, different meanings). Read PD1 to PD4 here, never the repo's D-numbers.
- **Git.** The uploaded repo has no `.git` folder, so "one task = one commit" cannot be followed as written. Rule 1 has the fallback.
- **iOS reference.** The iOS source is `Pear.-main/MediaHub/`. The repo's PROGRESS.md calls it `Pear_ios_app`; that name is wrong.
  Exact iOS lines for `HomeView.swift`: `loadLists` is 87-116, `feedRows` is 381-398.
- **Blocker B1 is already fixed in code.** `commonMain` no longer imports `java.awt`. `DesktopClipboard` is an `expect` with
  Android and desktop actuals. `BLOCKERS.md` still lists it as blocked; mark it RESOLVED when that file is next touched.
  B2 (the Android SDK for `:shared`) is still open and is what blocks the Gradle checks in section 4.
- **Overlap with the repo's "Still to do" list.** Tasks T1 to T3 cover E5 (blur and touch capability) from P6. The rest of P6
  (E2, E3, E6, E8, E9), E1 and P7 are not in this plan. Keep them on the list.
- **Decision record for T3.** Repo decision D5 says the video surface stays a `SurfaceView`. T3 adds a `TextureView` on the
  Liquid Glass path only, so T3 must record the change (step 6).
- **Themes-hidden switch (new in T9-a).** iOS hides every theme row when the built-in themes row is hidden. Kotlin never checked
  it. Fixed in T9-a.
- **Background save (T8-b.3).** A deliberate difference from iOS, which calls `save(isFinal: false)` on background. See T8-b.3.
- **T7 touch test.** The touch path is chosen with `Touch` or `Stylus`, not `!= Mouse`. Corrected in T7 step 1.
- **Build checks.** Until the B2 decision is made, the Gradle commands in section 4 cannot run. A missing Android SDK is not a
  code failure.

---

## Revision 3 (found while doing T5; applied in the code)

- **T5, subtitle lift.** `SubtitleLine` does not follow the margins automatically. It reads `PlayerMetrics.panelClearance`, which uses the un-margined bottom value, so on touch the subtitles would stay under the taller controls. It now reads `LocalPlayerMargins.current.panelClearance`.
- **T5, side panels** (source and track panels, `padding(top = 10, bottom = 10, end = 10)`). Using `margins.top`, `margins.bottom` and `margins.end` would change desktop (16, 44 and 72 dp against 10). The panels keep their 10 dp and add the system area at each edge (`sidePanelPadding()`). Desktop is unchanged.
- **T5, skip button.** `end = margins.end` would move it on desktop from 28 dp to 72 dp. It moves by the controls' extra margin instead (`margins.end - sideMarginDp`, and the same for the bottom). That difference is zero on desktop, so the button keeps its place above the controls.
- **T5, activity.** The `WindowInsets` members and `union` are marked `// VERIFY` in `MainActivity.kt`. Confirm the imports on the first build.

---

## 0. Ground rules (read first)

1. Do the tasks **in the order of section 3**. One task = one commit. Do not mix tasks. If the repo has no `.git` folder, write a patch per task instead (`diff -ruN` against a pristine copy, saved as `docs/android/patches/<task>.patch`) and say so in PROGRESS.md.
2. **Do not change how desktop looks or behaves.** Every Android-only change goes behind `PlatformCaps` (commonMain `expect`) or
   lives in `androidMain` / `androidApp`. If a commonMain change is needed, it must be a no-op on desktop.
3. Paths are relative to `shared/src/` unless they start with `androidApp/`. Package root: `com/mediahub/pear/`.
   `commonMain` = `commonMain/kotlin/com/mediahub/pear/`.
4. Do not add libraries. Do not rename existing prefs keys. Do not "clean up" code you were not asked to touch.
5. The sandbox usually has no Gradle and no device. If you cannot compile, say so. If you are not sure an API exists in
   Compose Multiplatform 1.12.1, Haze 2.0.1 or AGP 9.0.1, write `// VERIFY` next to it and list it in your report. Do not guess silently.
6. After each task, append a short entry to `docs/android/PROGRESS.md` using that file's status words
   (`WRITTEN`, `STUB-CHECKED`, `NEEDS-DEVICE`, `BLOCKED`). Say exactly which checks in section 4 are still owed.
7. If a task says **STOP AND REPORT**, stop that task, write down what you saw, and move to the next one.

---

## 1. What was found (summary)

| # | Symptom | Root cause | Evidence | Task |
|---|---------|------------|----------|------|
| A | Player controls look like flat dark tint while playing; light-rectangle artefacts when paused | Android video is a `SurfaceView`, which Haze cannot sample. `PlatformCaps.android.videoBlurSupported = false` makes `playerLook` pick `Tint` while playing (the "ugly tint"). While **paused** `playerLook` always returns `Blur`, so `GlassSurface` runs Haze over an empty source: the artefacts | `shell/PlayerScreen.kt:243`, `player/ui/PlayerLogic.kt:32-37`, `player/ui/PlayerSurfaces.kt` (`PlayerSurface`), `androidMain/.../player/VideoSurface.android.kt`, `glass/GlassSurface.kt` | T1 to T4 |
| B | Not enough padding left, right, bottom in the player | The player opens fullscreen on Android, and `MainActivity` drops **all** insets in fullscreen (cutout and gesture areas included). The player then uses the compact iOS margins (24 dp sides, 6 dp bottom), which on iOS sit inside the safe area | `androidApp/.../MainActivity.kt` (`val safe = if (fullscreen) Modifier else ...`), `player/ui/PlayerLogic.kt:160-176`, `player/ui/PlayerOverlay.kt:401,432,449` | T5 |
| C | Wide layout appears on phones | `layoutModeFor` is width-only (>= 640 dp). A phone in landscape is wider than that. A "Layout override" control in Settings also lets phone users force wide | `shell/LayoutMode.kt`, `shell/AdaptiveShell.kt:90`, `settings/SettingsScreen.kt` (Appearance, "Layout override"), `onboarding/OnboardingRules.kt:56` | T6 |
| D | Long press never shows the context menu | Every menu goes through `Modifier.clickOrMenu`. It only opens the menu for the **secondary mouse button**. Touch has no secondary button and there is **no long-press code at all**. Also its drag slop is a fixed **12 px** (about 4 dp on a phone), so many finger taps are read as drags and cancelled | `shell/Clicks.kt`, `androidMain/.../shell/Clicks.android.kt`. Callers: `home/PosterCard.kt`, `ThemeCarousel.kt`, `ContinueRow.kt`, `CatalogRowView.kt`, `detail/*`, `history/HistoryPages.kt`, `settings/*`, `Downloads/DownloadsScreen.kt` | T7 |
| E | Continue Watching "not working" | **Not proven by reading.** Likely contributors: (1) the 12 px slop in D makes the cards hard to tap, (2) a tap opens the source chooser, but iOS opens the **detail page**, (3) the resume card shows an "UP NEXT" badge and different text than iOS, (4) saving only fully updates the list when the player is disposed, and nothing is banked fully when the app goes to the background. A title also needs more than 30 s watched before it appears (same rule as iOS) | `home/ContinueRow.kt`, `history/WatchHistory.kt` (`isInProgress = position > 30`, `checkpoint` vs `update`), `player/PlaybackProgress.kt`, `shell/PlayerScreen.kt:92-150`, iOS `Features/HomeView.swift:712-870` | T8 |
| F | Android home has less content | Same `FeedRows` code on all platforms, but it is **not the iOS order**. iOS (`HomeView.swift:381-398`) shows themes at slots 0, 1, 2, then one after every second add-on row, then any left-over themes. Kotlin shows theme 0 and then only themes between add-on rows, so with few add-on rows (a fresh Android install only has Cinemeta) the other themes **never show**. Kotlin also has **no MDBList rows** at all (iOS `model.lists`). Settings do not carry over from desktop unless the user imports a backup | `home/HomeScreen.kt` (`FeedRows`), `home/HomeModel.kt`, `meta/MdbListClient.kt` (client exists, Home never calls it), `data/PrefKeys.kt:14` (`MDBLIST_LISTS` is never read or written by a feature; it appears only in the backup key list, `PrefKeys.kt:114`) | T9 |

---

## 2. Decisions this plan assumes (change them here if wrong)

- **PD1. "Frosted" on Android means a non-sampling frosted look, not a real blur.** A real blur needs the video to be
  sampleable (TextureView), and that is what costs battery. So:
  - **Frosted (default):** `SurfaceView` stays (cheapest path). Controls get a new `PlayerLook.Frosted`: a light, gradient, rimmed
    glass-like fill with **no black plate**, no Haze, no sampling.
  - **Liquid Glass (opt-in, Android-only setting + onboarding choice):** `TextureView` + Haze glass, same as desktop, with a battery warning.
- **PD2. Continue Watching tap on Android opens the detail page**, as iOS does (`NavigationLink(value: ResumeTarget...)`).
  Desktop keeps the source chooser. Switch with `PlatformCaps.isTouchFirst`.
- **PD3. Phones never get the wide layout.** Tablets do. "Tablet" = smallest screen width >= 600 dp (`smallestScreenWidthDp`, so
  rotating a phone never makes it a tablet). The layout override control is shown only on devices that can go wide.
- **PD4.** The Android-only glass choice is **not** part of settings backups (it is a device setting).

---

## 3. Tasks (do in this order)

Order: T7, T1, T6, T5, T9, T8, T2, T4, T3, T10. (T7 and T6 are small and unblock the rest. T3 is last because it is the riskiest.)

### T7. Touch long press and tap slop in `clickOrMenu`  (fixes D, helps E)

File: `commonMain/.../shell/Clicks.kt` (and nothing else, unless a compile error needs an import).

Current behaviour: the first `Press` decides the button. Secondary button -> `menu()` on release. Otherwise `click()` on release.
Movement over `CLICK_SLOP_PX = 12f` cancels.

Change, inside the existing `awaitEachGesture` (keep the mouse path **exactly** as it is, so desktop does not change):

1. Read the first change: `val first = down.changes.first()`. `val touch = first.type == PointerType.Touch`
   (`androidx.compose.ui.input.pointer.PointerType`). Stylus counts as touch too. Use `touch = first.type == PointerType.Touch || first.type == PointerType.Stylus` (this replaces the line above), **not** `first.type != PointerType.Mouse`: a desktop pointer reported as `Unknown` or `Eraser` would then take the touch path, and desktop must not change.
2. Get `val vc = viewConfiguration` from the `pointerInput` scope (`PointerInputScope.viewConfiguration`). Read
   `vc.longPressTimeoutMillis` and `vc.touchSlop` **outside** `awaitEachGesture`, inside the `pointerInput { }` block.
3. If `touch`:
   - use `slop = vc.touchSlop` instead of 12 px;
   - wait for one of: release inside slop (a tap -> `click()`), movement past slop (a drag -> cancel, do nothing),
     or `longPressTimeoutMillis` passing while still pressed inside slop (a long press).
     Use `withTimeoutOrNull(vc.longPressTimeoutMillis) { ...loop awaiting pointer events... }` returning a small sealed result
     (`Tap`, `Dragged`). `null` means long press.
   - on long press: emit `PressInteraction.Cancel(press)` to `source`, call haptic feedback, call `menu()`, then **swallow the rest
     of the gesture**: loop `awaitPointerEvent()` consuming every change until no pointer is pressed, so lifting the finger does
     not also fire `click()` and does not start a scroll.
   - haptic: `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`. `clickOrMenu` is a
     `@Composable Modifier` extension, so read `LocalHapticFeedback.current` at the top and use `rememberUpdatedState`.
4. If not `touch`: current code path, unchanged (12 px, secondary button -> menu).
5. Keep pressed-state visuals: still emit `Press` first, and `Release`/`Cancel` at the end, as now.

Do **not** switch to `detectTapGestures` or `combinedClickable` for this: both fire for mouse buttons too and would change desktop.

Optional polish, do only after the above works on a device (note it in PROGRESS.md as a separate item):
`shell/ContextMenu.kt` opens the menu beside the anchor (right, else left). On a phone, when `PlatformCaps.isTouchFirst`, open it
below the anchor (above if there is no room), centred on the anchor, clamped to the screen, like the iOS context menu.

Acceptance (device): long press on a poster, a Continue Watching card, an episode, a season chip, a source row, a download row, a
library item: each shows its menu and does **not** also open the item. A quick tap still opens it. Dragging a row still scrolls it and
shows no menu. Desktop right-click still works.

---

### T1. Player look rules: no more Blur over a SurfaceView, and a Frosted look  (fixes A: artefacts, tint)

Files: `commonMain/.../player/ui/PlayerLogic.kt`, `player/ui/PlayerSurfaces.kt`, `shell/PlayerScreen.kt`.

1. `enum class PlayerLook`: add `Frosted` (doc comment: "No sampling. A light glass-like fill for platforms whose video cannot be blurred").
2. `playerLook(...)`: add a **last** parameter `videoSampleable: Boolean = true` (the default keeps desktop unchanged).
   New `when`, in this order:
   ```kotlin
   !glassOn -> PlayerLook.Flat
   !videoSampleable -> PlayerLook.Frosted      // never Blur, never Tint: this is the artefact fix
   baseMode == GlassMode.Flat -> PlayerLook.Tint
   videoMoving && !blurWhilePlaying -> PlayerLook.Tint
   else -> PlayerLook.Blur
   ```
3. `PlayerSurfaces.kt`, function `PlayerSurface`: add a `PlayerLook.Frosted ->` branch to the `when`. Keep the pattern of the `Tint`
   branch (clip to `shape`, background, then `wrappedContent`). Starting values, to be tuned on a device (put them in named
   `private val` constants at the top of the file):
   - Circle and Capsule: base `Color.Black.copy(alpha = 0.12f)`, then a vertical gradient of `Color.White` from alpha `0.26f` (top) to
     `0.14f` (bottom) drawn over it. This must **not** read as a dark plate.
   - Card: base `Color.Black.copy(alpha = 0.28f)`, white gradient `0.10f` to `0.06f` (cards carry text and need more contrast).
   - If `tint != null`: `tint.copy(alpha = 0.55f).compositeOver(base)` as in the `Tint` branch.
   - Rim: a 0.5 dp border with a vertical brush, white alpha `0.45f` at the top to `0.10f` at the bottom (a specular edge).
     `wrappedContent` already adds a flat rim, so for this branch build your own content wrapper without that flat rim.
   - No shadow, no blur, no `graphicsLayer`/`RenderEffect` (battery).
4. `PlayerScreen.kt:243`: pass `videoSampleable = ...`. For this task use
   `videoSampleable = PlatformCaps.videoBlurSupported` (so Android, currently `false`, gets `Frosted`; desktop stays `true`).
   T2 replaces this with the real rule.

Acceptance (device): while playing **and** paused, no dark plate and no light rectangles. White icons are readable on a bright frame
(a snowy or white scene) and on a dark frame.

---

### T6. Wide layout only on tablets  (fixes C)

Files: `commonMain/.../shell/PlatformCaps.kt`, `androidMain/.../shell/PlatformCaps.android.kt`,
`desktopMain/.../shell/PlatformCaps.desktop.kt`, `commonMain/.../shell/LayoutMode.kt`, `shell/AdaptiveShell.kt`,
`onboarding/OnboardingRules.kt`, `onboarding/OnboardingHost.kt`, `settings/SettingsScreen.kt`.

1. `PlatformCaps` (expect): add `val supportsWideLayout: Boolean` with doc "The device may use the sidebar layout: a desktop window,
   or a tablet. Phones never."
   - desktop actual: `true`.
   - Android actual: a getter (not a constant) so it follows the current configuration:
     `get() = AndroidHost.app().resources.configuration.smallestScreenWidthDp >= 600`.
     Add a named constant `TABLET_MIN_SMALLEST_WIDTH_DP = 600`.
2. `LayoutMode.kt`: change the signature to
   `fun layoutModeFor(windowWidth: Dp, override: LayoutOverride = LayoutOverride.Auto, allowWide: Boolean = true): LayoutMode`.
   First line: `if (!allowWide) return LayoutMode.Compact`. The rest is unchanged.
3. Call sites pass `allowWide = PlatformCaps.supportsWideLayout`:
   - `shell/AdaptiveShell.kt:90`
   - `onboarding/OnboardingRules.layoutFor(...)`: add the same parameter with the same default, and pass it from `OnboardingHost.kt:228`.
4. `settings/SettingsScreen.kt` (Appearance group, the block with "Layout override" and its `SegmentedControl`, with the `SettingsDivider()`
   above it): wrap both in `if (PlatformCaps.supportsWideLayout)`.

Acceptance (device): a phone in portrait **and** landscape always shows the bottom tab bar and the phone onboarding. A tablet (or an
emulator with smallest width >= 600 dp) shows the sidebar and the override control. Settings on a phone has no layout control.

---

### T5. Player margins and insets  (fixes B)

Files: `commonMain/.../player/ui/PlayerLogic.kt` (pure function), `player/ui/PlayerOverlay.kt`, `player/ui/PlayerPanels.kt`,
`player/ui/TrackPanels.kt`, `player/ui/SkipOverlay.kt`, `player/ui/SubtitleLine.kt`, `androidApp/.../MainActivity.kt`.

Why: the margins are measured from the physical screen edge in Android fullscreen (see section 1, B).

1. In `PlayerLogic.kt` add:
   ```kotlin
   /** System areas the player's controls must stay clear of, in dp. Zero on desktop. */
   data class PlayerInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

   /** The controls' margins on a touch screen: never less than the minimums, and always clear of the insets. */
   data class PlayerMargins(val start: Float, val end: Float, val top: Float, val bottom: Float)

   fun playerMargins(metrics: PlayerMetrics, insets: PlayerInsets, touch: Boolean): PlayerMargins
   ```
   Rules. Not touch: `start = end = metrics.sideMarginDp`, `top = 16f`, `bottom = metrics.bottomMarginDp` (desktop exactly as today).
   Touch: `start = max(48f, insets.left + 24f)`, `end = max(48f, insets.right + 24f)`, `top = max(24f, insets.top + 12f)`,
   `bottom = max(28f, insets.bottom + 16f)`. Put the numbers in named constants (`TOUCH_MIN_SIDE_DP = 48f` and so on) with the comment
   "tune on a device". Divide the inset values by `metrics.scale` first, because the overlay draws in a scaled density.
2. `PlayerOverlay.kt`: add `val LocalPlayerInsets = compositionLocalOf { PlayerInsets() }` (**public**, because `androidApp` is another module).
   Inside `BoxWithConstraints`, compute `margins = playerMargins(metrics, LocalPlayerInsets.current, PlatformCaps.isTouchFirst)` and provide it
   with the other `CompositionLocalProvider` values (new `LocalPlayerMargins`, internal).
3. Replace the hard-coded margin uses:
   - `PlayerOverlay.kt:401` and `:432` (`padding(start = ...sideMargin, top = 16.dp)`): use `margins.start` and `margins.top`.
   - `PlayerOverlay.kt:449` (bottom pill and seek bar: `horizontal = sideMargin`, `bottom = bottomMargin`): use `start`, `end`, `bottom`.
   - `PlayerOverlay.kt:298`: `availableWidthDp` should subtract `margins.start + margins.end` instead of `2 * sideMarginDp`
     (compute `margins` before `metrics.copy`, so build it from `fitted`).
   - `PlayerOverlay.kt:588` (notice at the top centre, `top = 18.dp`): `margins.top + 2.dp`.
   - `PlayerPanels.kt:55`, `:309` and `TrackPanels.kt:166`: `end = margins.end`, `bottom = panelClearance` where
     `panelClearance = margins.bottom + 104.dp`.
   - `PlayerPanels.kt:118` and `TrackPanels.kt:81` (`padding(top = 10, bottom = 10, end = 10)`): use `margins.top`, `margins.bottom`, `margins.end`.
   - `SkipOverlay.kt:104` (`end = 28.dp`): `margins.end`; its `bottom` must clear the controls the same way as before, plus the new bottom margin.
   - `SubtitleLine.kt:105` (`lift`): uses `panelClearance`, so it follows automatically. Check it.
   - Keep `PlayerMetrics.panelClearance` working for desktop (same value as today when `touch` is false).
4. `androidApp/.../MainActivity.kt`: stop discarding the insets for the player. Keep the video itself edge to edge; only the **controls** are inset.
   - Compute, inside `setContent`, the union of `WindowInsets.displayCutout`, `WindowInsets.systemBarsIgnoringVisibility` and `WindowInsets.systemGestures`
     (`union`), read per side with `getLeft/getTop/getRight/getBottom(density, LayoutDirection.Ltr)`, converted to dp.
     (`...IgnoringVisibility` is needed because the bars are hidden in fullscreen, so the plain versions would be 0.)
   - Provide `LocalPlayerInsets` with that value **only when `fullscreen` is true**. When not fullscreen, provide zero (the app is already padded by
     `safeDrawing`, so adding it again would pad twice).

Acceptance (device): in landscape fullscreen the close button, the title, the bottom pill, the seek bar and the open panels are clearly away from the
left and right edges, the notch, the rounded corners and the gesture bar. On a phone without a notch the margin is still visibly larger than before.
Desktop is pixel-identical.

---

### T9. Home feed order and MDBList rows, same as iOS  (fixes F)

Spec: iOS `Features/HomeView.swift` lines 87-116 (`loadLists`) and 381-398 (`feedRows`). Files: `commonMain/.../home/HomeScreen.kt`,
`home/HomeModel.kt`, a new `home/HomeFeedOrder.kt`.

Be aware: this changes the desktop feed too, because desktop is the wide layout of the same iOS design. That is intended (iOS parity).

**T9-a. Order (do this first, it fixes the "one theme carousel" symptom).**

1. New file `home/HomeFeedOrder.kt` with a pure function:
   ```kotlin
   sealed interface FeedSlot {
       data object Continue : FeedSlot
       data class Theme(val index: Int) : FeedSlot
       data object Suggested : FeedSlot      // the suggested rows, all of them, in order
       data object Lists : FeedSlot          // the MDBList rows, all of them, in order
       data class Addon(val index: Int) : FeedSlot
   }
   fun feedOrder(showContinue: Boolean, themeCount: Int, addonRowCount: Int): List<FeedSlot>
   ```
   The order, exactly as iOS:
   1. `Continue` if `showContinue`.
   2. `Theme(0)` if `themeCount > 0`.
   3. `Suggested`.
   4. `Theme(1)` if it exists.
   5. `Lists`.
   6. `Theme(2)` if it exists.
   7. For each add-on row `i` in `0 until addonRowCount`: `Addon(i)`, and after it, if `i % 2 == 1` and `Theme(3 + i / 2)` exists, that theme.
   8. Trailing themes: every theme with index `>= 3 + addonRowCount / 2`.
   A theme "exists" when `index < themeCount`.
2. `HomeScreen.kt`, `FeedRows`: replace steps 2 to 4 of the current body with a loop over `feedOrder(...)`. Pass `themeCount = if (rules.isRowHidden(BuiltinRows.THEMES)) 0 else model.themes.size` (iOS `themesHidden`, `HomeView.swift:287`; both platforms use the key `builtin:themes`). Without this, a user who hid the themes row still sees themes on Android. Keep the existing composables for each slot
   (`ContinueRow`, `ThemeCarousel`, `CatalogRowView`) and keep hiding rows with `rules.isRowHidden(row.prefKey)`. **Count `addonRowCount` after hiding**, as iOS does
   (`visible(model.rows)`), and for `Suggested` use the already-filtered list.

**T9-b. MDBList rows on Home.**

1. `HomeModel`: add `var lists: List<CatalogRow> by mutableStateOf(emptyList())` (private set) and `suspend fun loadLists(rules: ContentRules)`.
   Port iOS `loadLists` (HomeView.swift:87-116):
   - read the selected ids from `data.prefs.string(PrefKeys.MDBLIST_LISTS, "")`, split on `,`, `toIntOrNull`;
   - if `!data.meta.mdblist.hasKey` or none selected: `lists = emptyList()`, return;
   - `val chosen = data.meta.mdblist.userLists().filter { it.id in selected }`;
   - fetch each list's `items(listId)` in parallel (`async` inside `coroutineScope`), filter with `data.classifier.filter(raw, rules)`;
   - a row is `CatalogRow(id = "mdb-${l.id}", title = l.name, items = items, symbol = "list.star")`, skipped when `items` is empty;
   - on a first load, show rows as they arrive, in list order; at the end set `lists` in list order;
   - if nothing came back but `lists` was not empty (MDBList unreachable), keep the old rows minus any that were deselected, re-filtered.
2. In `refresh()`, start `loadLists(rules)` next to the other `async` jobs and `await` it.
3. `HomeScreen.kt` `LaunchedEffect` keys: add `PrefKeys.MDBLIST_LISTS` and the MDBList key
   (`rememberPref(env.data.prefs, PrefKeys.MDBLIST_LISTS) { it.string(PrefKeys.MDBLIST_LISTS, "") }` and the same for `MDBLIST_KEY`) so changing them in Settings refreshes Home.
4. In `FeedRows` render the `Lists` slot with the same `CatalogRowView` call the add-on rows use (see-all handled the same way; if `source` is `None` the row has no
   "See all", check `CatalogRowView` copes).

**T9-c. MDBList picker in Settings (separate commit, after T9-a and T9-b).** iOS: `Features/MDBListPicker.swift`. Kotlin has only the key field
(`settings/IntegrationsScreen.kt:92`). Without the picker `MDBLIST_LISTS` is never set, so the MDBList rows never appear. Add a picker under the key field: when
a key is present, call `userLists()`, show each list with a check mark, and write the selected ids as a comma-separated string to `PrefKeys.MDBLIST_LISTS`
(same format iOS reads). Follow the look of the existing Settings rows.

Acceptance (device or desktop): with Cinemeta only and a TMDB source, Home shows Continue (if any), a theme, the trending rows, a theme, [MDBList rows], a theme,
the Cinemeta rows, then the remaining themes. A user who also set MDBList lists sees them. Nothing is shown twice. With the built-in themes row hidden, no theme rows appear.

User-side note (no code): importing a settings backup from desktop on Android (Settings, backup) brings add-ons, keys and pinned lists
(`BackupRules.keys` includes `ADDON_MANIFEST_URLS` and `MDBLIST_LISTS`). History is only included when "include data" is chosen.

---

### T8. Continue Watching  (fixes E)

Files: `commonMain/.../home/ContinueRow.kt`, `shell/PlayerScreen.kt`, `player/PlaybackProgress.kt`, `androidApp/src/main/AndroidManifest.xml`,
`androidApp/.../MainActivity.kt`, `androidApp/.../PearApplication.kt`. iOS spec: `Features/HomeView.swift:712-870`.

**T8-a. Find out what is actually failing (do this before changing behaviour).**

1. Add temporary `println("PEAR-CW ...")` lines (they reach logcat as `System.out`). Mark each with `// TEMP-CW`:
   - `PlaybackProgress.save`: print `position`, `duration`, `canSave`, `reachedStart`, `isFinal`.
   - `PlayerScreen.kt` `leave`: print that it ran, and the snapshot.
   - `WatchHistory.update` and `checkpoint`: print the item id, key, position.
   - `HomeScreen.FeedRows`: print `continueEntries.size` and `upNext.size` on every recomposition of the row.
2. Scenario to run, and what each result means (write the outcome in PROGRESS.md):
   - Play a film for **more than 40 s**, press back (twice if fullscreen asks first), go to Home.
     - No `save ... canSave=true` line: the position or duration is 0. Look at `AndroidMpvEngine` (`time-pos`, `duration` observers) and whether the stream reports a duration.
       Also check `leave` ran at all. **STOP AND REPORT** with the log.
     - `save` and `update` lines appear but Home prints 0 entries: a profile mismatch (`WatchHistory.storeKey(profileID)` vs the active profile) or `entries` not refreshed.
       Check `data.history.load(profile)` on profile switch and on Android start.
     - Home prints entries > 0 but no card is visible: it is a layout problem in the compact `HomeScreen` branch.
     - The card is visible but a tap does nothing: it is the slop bug (fixed by T7). Re-test after T7.
   - Play for **less than 30 s** and leave: the title must **not** appear. That is the iOS rule (`isInProgress = position > 30`). Not a bug.
   - Press the Home button while playing (not back), reopen the app: check the log shows a save and that the row appears.
3. Remove every `// TEMP-CW` line when done.

**T8-b. Make it match iOS (do these regardless of T8-a).**

1. **Tap target.** In `ContinueRow.kt`, in the `onClick` of both `ContinueCard` and `UpNextCard`: when `PlatformCaps.isTouchFirst` call
   `onSelectTitle(item, season, episode)` (opens the detail page with the season and episode selected, as iOS `DetailView(startSeason:startEpisode:)`).
   Otherwise keep `choose(...)` (source chooser). Keep `choose` for the desktop path.
2. **Resume card look** (iOS `ContinueCard`). Applies to all platforms:
   - Remove the "UP NEXT" badge from the in-progress card. It stays only on `UpNextCard`.
   - Add a pill at the top right: `Fmt.clock(entry.position)` (`history/WatchHistory.kt:380`), white 11 sp bold, monospaced digits, black 62% capsule, 8 dp padding.
   - Add a bottom gradient overlay (transparent from the middle to black 50% at the bottom).
   - Progress bar: 4 dp tall, track white 30%, fill = the accent gradient, width = `entry.progress`.
   - Subtitle: `"S$s · E$e · ${Fmt.remaining(entry.duration - entry.position)}"` for an episode, `Fmt.remaining(...)` for a movie. (The episode title is **not** shown on iOS.)
   - Size: card width 270 dp, spacing 14 dp between cards, 16 dp content margin (the Kotlin code has 260, 16, 20). Do the same for `UpNextCard`.
   - `UpNextCard` subtitle on iOS is `S · E · episode title`, keep that.
3. **Bank progress when the app goes to the background.** In `PlaybackProgress` add `fun bank(request, snapshot)` that runs `logPlayTime` and `save(..., isFinal = true)`
   **without** setting `finished`. In `PlayerScreen.kt`, in the `AppLifecycle.backgrounded.collect { ... }` block, call it before `engine.pause()` (use the snapshot from before the pause).
   This makes `entries` (not only the stored JSON) current even if Android kills the process while the app is in the background. This is a deliberate difference from iOS: `PlayerView.swift:645-646` calls `save(isFinal: false)` on background. On Android, `isFinal = false` calls `history.checkpoint`, which does not refresh `entries`, so the Continue row would stay stale. `isFinal = true` calls `history.update`, which does. List this in `IOS_PARITY.md` (T10).
4. **Stop the activity restarting.** In `AndroidManifest.xml` extend `configChanges` with `density|uiMode|fontScale|locale|layoutDirection|colorMode`. Today a
   dark-mode switch or font-size change recreates the activity, builds a second `PrefStore` (storage is created per activity with `by lazy`), and can drop a
   pending history write. Also move `androidStorage(...)` from `MainActivity` to a single instance held by `PearApplication` (create it in `onCreate`, read it from the activity).

Acceptance (device): play 2 minutes, back out: a card is there with the right still, the time pill, the progress bar and "S1 · E2 · 38 min left". Tap it: the detail page opens
on that episode. Long press it: the menu shows "Remove from Continue Watching" first, then the title menu. Remove it: it goes. Press the Home button while playing, kill the app
from recents, reopen: the card is still there.

---

### T2. Android-only player glass setting (Frosted or Liquid Glass) with battery warning  (user request)

Files: `commonMain/.../data/PrefKeys.kt`, `shell/PlatformCaps.kt` (+ 2 actuals), `shell/PlayerScreen.kt`, `settings/SettingsScreen.kt`.

1. `PrefKeys.kt`: add `const val ANDROID_PLAYER_LIQUID_GLASS = "android.playerLiquidGlass"` next to the `DESKTOP_*` keys. Default **false**. Do **not** add it to `BackupRules.keys` (PD4).
2. `PlatformCaps` (expect): add `val hasPlayerGlassChoice: Boolean`. Android actual `true`; desktop actual `false`.
   Change the Android actual `videoBlurSupported` to `true` **only when T3 is done**; until then leave it `false`. Its meaning becomes "this platform can blur the video when asked".
3. `PlayerScreen.kt` (line ~239-243): replace the look logic with
   ```kotlin
   val liquid = rememberPref(prefs, PrefKeys.ANDROID_PLAYER_LIQUID_GLASS) { it.bool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false) }
   // Decided once when the player opens: swapping the surface type during a film would flash.
   val sampleable = remember {
       if (PlatformCaps.hasPlayerGlassChoice) liquid && PlatformCaps.videoBlurSupported && !PowerStatus.lowBattery
       else PlatformCaps.videoBlurSupported
   }
   val look = playerLook(glassOn, env.glass.mode, isVideoMoving(state),
       blurWhilePlaying = if (PlatformCaps.hasPlayerGlassChoice) true else blurPlaying && PlatformCaps.videoBlurSupported,
       videoSampleable = sampleable)
   ```
   and pass `sampleable` to `VideoSurface` (T3). Battery saver on means Frosted, which is intended.
4. `SettingsScreen.kt`, Playback group (lines ~183-205):
   - On Android (`PlatformCaps.hasPlayerGlassChoice`) rename the existing `PLAYER_GLASS` row to **"Glass controls"** (desktop and iOS keep "Liquid Glass controls").
   - Under it, shown only when `playerGlass && PlatformCaps.hasPlayerGlassChoice`, add a row **"Liquid Glass"** with the subtitle
     "Live blur and refraction behind the controls. Can use more battery." and a `PearSwitch` bound to `ANDROID_PLAYER_LIQUID_GLASS`.
   - Turning it **on** opens a confirm dialog (copy the pattern of `showP2PDialog`, lines ~403-430: dim overlay, card, two `PearCapsuleButton`s). Text:
     title "Use Liquid Glass in the player?", body "Liquid Glass blurs the video live behind the controls. It looks best, but it can drain the battery faster and
     make older phones warm or drop frames. You can switch it off any time in Settings.", buttons "Keep Frosted" (cancel, leaves the switch off) and "Use Liquid Glass"
     (sets the pref to true). Turning it off needs no dialog.
   - Hide the desktop row "Blur controls while playing" when `hasPlayerGlassChoice` (Liquid already blurs while playing). Change its condition to
     `playerGlass && PlatformCaps.videoBlurSupported && !PlatformCaps.hasPlayerGlassChoice`.
5. Everything outside the player keeps Liquid Glass (`GlassSurface`, `GlassStore`): do not touch it.

Acceptance (device): fresh install: Settings, Playback shows "Glass controls" on and "Liquid Glass" off. Turning Liquid Glass on shows the warning; "Keep Frosted" leaves it off.
Reopen the player after changing it: the new look applies. Battery saver on: Frosted regardless.

---

### T4. Onboarding: choose the player glass (Android only)

Files: `commonMain/.../onboarding/OnboardingRules.kt`, `OnboardingCommon.kt`, `OnboardingSteps.kt`, `OnboardingHost.kt` (only if it counts steps). Depends on T2.

1. `OnboardingRules.kt`: add `Player` to `enum class OnboardingStep` **between `Look` and `Content`**. Add
   `val steps: List<OnboardingStep> get() = OnboardingStep.entries.filter { it != OnboardingStep.Player || PlatformCaps.hasPlayerGlassChoice }` to `OnboardingRules`.
   Change `next()` and `previous()` to walk `OnboardingRules.steps` (index of `this` plus or minus 1), so desktop never sees `Player`.
2. `OnboardingCommon.kt`: add the `Player` case to `stepTitle` ("Player glass"), `stepSubtitle` ("Pick how the player's buttons look. You can change it any time in Settings, Playback."),
   `railLabel` ("Player"). `ProgressDots` (line ~265) must loop over `OnboardingRules.steps` instead of `OnboardingStep.entries`.
3. `OnboardingSteps.kt`: add `OnboardingStep.Player -> PlayerGlassBody(wide)` to the `when` (line ~54) and write `PlayerGlassBody`:
   two choice cards in an `OnboardingCard` (reuse the look of `CategoryToggle` or the colour swatch cards: a title, a short line, a selected state):
   - **"Frosted glass"**, line "Soft, light and easy on the battery." Tag "Recommended". Selected by default.
   - **"Liquid Glass"**, line "Live blur and refraction, like the rest of the app." and, always visible under it, a warning line in the orange colour
     (`PearTheme.colors.orange`) with the exclamation icon: "Can use more battery, especially on long films and older phones."
   Tapping a card writes `PrefKeys.ANDROID_PLAYER_LIQUID_GLASS` straight away (`false` for Frosted, `true` for Liquid), as the accent picker writes its choice.
   Read the current value so the selection survives Back and Next. `wide` uses `TwoUp` with the existing `PreviewCard()` on the right if that fits, otherwise a single column.
4. Any other place that does `when (step)` exhaustively will fail to compile until it has a `Player` branch. Fix each (`grep -rn "OnboardingStep\." commonMain`).

Acceptance (device): a new Android install shows the extra step after the colour step, with Frosted selected. Choosing Liquid Glass and finishing sets the Settings switch on. Desktop onboarding is unchanged.

---

### T3. Liquid Glass surface: TextureView so Haze can see the video  (needed for T2's Liquid option)

Do this **last**, it is the riskiest. Files: `commonMain/.../player/VideoSurface.kt` (expect), `desktopMain/.../player/mpv/VideoSurface.desktop.kt`,
`androidMain/.../player/VideoSurface.android.kt`, `shell/PlayerScreen.kt:657`, `androidMain/.../shell/PlatformCaps.android.kt`.

1. `expect fun VideoSurface(...)`: add a last parameter `sampleable: Boolean = false`. (A default on the `expect` declaration is allowed; the actuals must **not** repeat the default.)
   Desktop actual: add the parameter and ignore it (desktop frames are drawn into a Compose canvas, always sampleable).
   `PlayerCheckScreen.kt:72` keeps compiling through the default.
2. `PlayerScreen.kt:657`: pass `sampleable = sampleable` (the value from T2).
3. `VideoSurface.android.kt`: keep the current `SurfaceView` path as `SurfaceVideo(...)`. Add `TextureVideo(...)` and choose with `if (sampleable) TextureVideo(...) else SurfaceVideo(...)`
   wrapped in `key(sampleable)`. `TextureVideo` uses `AndroidView` with a `TextureView` and this listener (the same engine calls the SurfaceView path uses):
   ```kotlin
   object : TextureView.SurfaceTextureListener {
       private var surface: Surface? = null
       override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
           val s = Surface(st); surface = s
           mpv?.attachSurface(s); mpv?.surfaceSizeChanged(w, h)
       }
       override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { mpv?.surfaceSizeChanged(w, h) }
       override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
           mpv?.detachSurface(); surface?.release(); surface = null
           return true
       }
       override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
   }
   ```
   Detach **before** releasing the `Surface`. Keep the double-tap `pointerInput` and the black background exactly as in the SurfaceView version.
   `onRelease` must clear the listener (`setSurfaceTextureListener(null)`).
4. `PlatformCaps.android.kt`: set `videoBlurSupported = true`.
5. Verify on a device (these are the checks that decide if this task is done):
   - Liquid Glass on: the controls blur the live video (not a black or empty blur), no rectangles, paused and playing.
   - Rotation, entering and leaving fullscreen, Picture in Picture and returning from the background: the picture comes back, with no black frame and no restart.
   - Seeking and pausing show no tearing.
   - Frosted (default): behaviour and battery are the same as before this task (still a `SurfaceView`).
   **STOP AND REPORT** if the blur samples black or transparent (Haze not capturing the TextureView). Do not try workarounds (PixelCopy, screenshots) without the owner agreeing.
   If it plays but drops frames on a 4K or HDR file, report it with the device model; do not change decoder options.
6. **Decision record.** Repo decision D5 (`docs/android/decisions.md`) says the video surface stays a `SurfaceView`. This task reverses that for the Liquid Glass path only; Frosted keeps the `SurfaceView`. Add a dated note under D5 saying so before the device checks run.

---

### T10. iOS 1:1 audit (a document, not code)

Create `docs/android/IOS_PARITY.md`. For each screen below list: the iOS file, the Kotlin file, what differs (layout, sizes, text, behaviour, gestures), and a proposed fix with a size
(S, M, L). **Do not implement these**, only the items already in T1 to T9. Use the same status words as PROGRESS.md.

| Screen | iOS file (`MediaHub/Features/` unless noted) | Kotlin |
|---|---|---|
| Home, hero, rows, Continue | `HomeView.swift` | `home/*` |
| Theme rows | `Themerows.swift` | `home/ThemeCarousel.kt`, `meta/ThemeCatalog.kt` |
| Detail | `DetailView.swift`, `EpisodeCard.swift`, `TrailerCard.swift` | `detail/*` |
| Player | `PlayerView.swift`, `PlayerPiP.swift`, `PlayerInfoCard.swift` | `player/ui/*`, `shell/PlayerScreen.kt` |
| Search, Explore, Library | `SearchView.swift`, `ExploreView.swift`, `LibraryView.swift` | `search/*`, `explore/*`, `library/*` |
| Settings, Onboarding | `SettingsView.swift`, `OnboardingView.swift` | `settings/*`, `onboarding/*` |
| Downloads, Profiles | `DownloadsView.swift`, `ProfileViews.swift` | `Downloads/*`, `profile/*` |

Already known deviations to list (found while writing this plan): home feed order and MDBList (T9), the themes-hidden switch (T9-a), background save with `isFinal = true` (T8-b.3), Continue card look and tap target (T8), long press (T7), margins (T5),
wide layout rules (T6), home horizontal padding (Kotlin 20 dp vs iOS 16 dp), the Continue card sizes (T8), context menu placement (T7 polish), the player glass naming (T2).

---

## 4. Device checklist (run after all tasks; record results in PROGRESS.md)

Devices: one phone (check portrait and landscape), and a tablet or an emulator with smallest width >= 600 dp.

1. Phone landscape and portrait: bottom tab bar only; Settings has no layout control; onboarding is the phone frame. (T6)
2. Tablet: sidebar layout and the layout control exist. (T6)
3. Player, Frosted (default): playing and paused look the same family, no dark plate, no rectangles, readable on a bright and a dark scene. (T1)
4. Player margins: left, right, bottom clear of the notch, corners and gesture bar in landscape fullscreen; panels and the Skip button follow. (T5)
5. Settings, Playback: "Glass controls" and "Liquid Glass" rows; the warning dialog; default off. (T2)
6. Onboarding on a fresh install: the player glass step, Frosted preselected, warning visible on Liquid Glass. (T4)
7. Player, Liquid Glass on: real blur of the live video, no artefacts, survives rotation, fullscreen, PiP and background. (T3)
8. Long press on posters, theme cards, Continue cards, episodes, season chips, sources, downloads, library: menu, no accidental open; quick tap opens; drag scrolls. (T7)
9. Continue Watching: card after 40 s of play; look matches iOS; tap opens detail on the right episode; remove works; survives Home button and kill. (T8)
10. Home with Cinemeta and a TMDB source: the feed order of T9-a. With MDBList lists chosen: the list rows appear. (T9)
11. Desktop smoke test (must be unchanged): player glass, right-click menus, wide layout, onboarding steps.

Build checks to run when Gradle is available: `gradle :shared:compileKotlinDesktop -PlibmpvSkip=true`, `gradle :androidApp:assembleDebug`.

---

## 5. Report format (what to hand back after each task)

- Task id and one line on what changed.
- Files touched.
- Status words, and which items of section 4 are still owed.
- Every `// VERIFY` you left and why.
- Anything you did not do and why.

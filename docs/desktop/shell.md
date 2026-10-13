# Adaptive shell and navigation (Batch 1.4)

Spec: `RootView` in MediaHubApp.swift and `SidebarShell` in `Features/WideLayout.swift`. Code: `shared/.../shell/` and `nav/`.

## Layouts
- **Expanded** (window >= 640 dp): the iPad sidebar. Width 250 dp from 1000 dp, 212 dp from 780 dp, icon-only 76 dp below. Header with avatar, name and
  clock; the five destinations in iPad order (Search, Home, Explore, Settings, Library); "Catalogues" list (hidden in the icon-only form). Selected row
  is filled with the accent and uses `onAccent`. Same sizes, radii and opacities as the Swift code.
- **Compact** (narrower): the iPhone tab bar: a floating glass capsule with Home, Explore, Library, Settings, and Search as a separate round glass
  button, like the iOS 26 `TabView`. Pages scroll under it and blur through it (`LocalBottomInset` tells pages how much to pad).
- 640 dp is my choice for "iPad regular size class"; change `Breakpoints.expandedMinWidth` if it feels wrong.
- Visited tabs stay composed while hidden and keep their own page stack and scroll position (`KeepAlive`). `LocalPageActive` is false for hidden
  pages so loops (hero auto-advance, Ken Burns) can rest, like `EnvironmentValues.pageActive`.

## Navigation stack (`nav/NavStack.kt`)
`NavController` (`push`, `pop`, `popToRoot`, `canGoBack`) and `NavHost`. A pushed page slides in from the right while the one below drifts left
(30%) and dims; pop plays it backwards (350 ms, iOS curve). Pages below the top stay composed but are not measured, drawn or clickable. No extra library.
Back: the on-screen button, Alt+Left, Esc. Ctrl+1..4 switch tab, Ctrl+F opens Search.
Not done yet: swipe-back by touch (Phase 5.3), tab bar minimise on scroll (5.1), mouse "back" button (4.6).

## Dev tools (Settings -> Developer)
Theme preview, Glass gallery, a push-page demo, a layout override (Auto / Phone / Wide) and a **phone-sized window** toggle (390 x 844, restores the
previous size). These are the component gallery for screenshot comparison. Pass `devTools = false` to `App()` to hide them.

## Placeholders
Every page is a placeholder (`shell/Pages.kt`) that names the Swift file and batch that will replace it. The page structure (scroll area under a floating
`GlassTopBar`, bottom padding for the tab bar) is the one the real pages should use.

## Wide-layout sizes (episode cards, theme cards, hero)

Only the wide layout (`LocalWideLayout`, the sidebar shell) changes. The phone layout keeps its sizes: every call site that
is shared between the two passes a scale of 1x when the layout is compact.

- `shell/LayoutMode.kt` `WideScale`: `CARD` = 1.33 (episode cards and theme carousel cards, a third bigger) and
  `HERO_WIDTH` = 1.10 (the wide home hero, 460 dp -> 506 dp). Change the numbers there.
- `EpisodeCard(scale)` scales the whole card (frame, padding, icons, type), not only its frame. At 1.0 the values are the
  ones the card always had.
- `ThemeCarousel` scales its cards when the wide layout is on. Explore uses the same carousel, so it gets the change too.
- Episodes on two rows (wide detail page): `DragLazyGrid` in `shell/DragScroll.kt` (a `LazyHorizontalGrid` with the same
  drag-to-scroll as `DragLazyRow`). The Seasons header has a One row / Two rows switch, shown only in the wide layout.
  The choice is the desktop-only pref `desktop.episodesTwoRows` (default: two rows). It is not in settings backups.

## Profiles: the avatar and "Who's watching?"

- The sidebar header (wide layout) and a button in the top-right corner (phone layout) show the active profile's avatar:
  its colour, icon, or Simkl photo (`ProfileAvatarButton`, the same `ProfileAvatar` as the sheet and the editor). Tapping it
  opens "Who's watching?" over the app (`ProfileSheetOverlay`, hosted by `AdaptiveShell`). A tap outside the sheet closes it.
- From the sheet: switch profile, "View ... profile" (opens `Route.ProfileDetail`), Downloads, and Manage Profiles (add, edit,
  delete). The shell's `showProfiles` state owns it.
- Home reloads when the active profile changes (`HomeScreen` keys its refresh on `profiles.activeID`), because the rows are
  filtered by the profile's content rules.

## Artwork sizes

- Wide hero column and the detail page's poster column use `MetaPreview.largePosterUrl()`: TMDB's original poster file, or
  metahub's large size. The list cards keep the small sizes.
- `ImageMemoryCache` is capped by decoded bytes (256 MB) as well as by entry count, because a full-size poster is about 24 MB
  as pixels.

## Wide home hero: logo, ratings and layout

- The title is the title's logo when there is one (add-on logo, else TMDB's, as on the detail page); the title in caps is the fallback.
- The ratings row is MDBList's (IMDb, Rotten Tomatoes and the rest, as `RatingBadge` on the detail page). It is hidden without an
  MDBList key. The old hard-coded "83%" is gone.
- Loading: `rememberTitleExtras` resolves the IMDb id for TMDB items, then fetches the logo and the ratings. The result is kept only for
  the title it was loaded for, so one title never shows another's logo while the carousel moves on.
- Layout follows the mockup: type pill, logo, ratings, synopsis and Details stack from a fixed distance above the bottom edge (Details
  136 dp up), and the dots sit below them, centred. The phone carousel is unchanged.

## Overlays, the source picker and the ambient colour

- Anything that floats over the app is drawn by `ShellOverlays` inside the glass scene, in both layouts. A glass surface blurs
  what its `GlassScene` captured in the background, so an overlay drawn inside a page (which is part of that background) comes out
  transparent. "Who's watching?" was drawn outside the scene and so had no glass at all.
- The source picker is `ShellState.sourcePicker`, opened by the detail page (`openSourcePicker`) and drawn by `SourcePickerOverlay`.
  Phone: the bottom sheet (`SourceSheet`). Wide: a centred panel, about 84% of the window (up to 980 x 720 dp), with the sources as
  a grid of cards and the add-on filter across the top.
- The ambient glow (`AmbientWash`) is iOS's top fade: the hero's colour at 70% from the top, easing to 25% at the middle and to clear at 1100 dp. It is drawn behind the app in both layouts, but only on the two pages that have a hero, Home and a title's detail page. Elsewhere it fades out (`ambientStrength`, 1.8 s), so the glass there is not tinted by a poster. The phone hero takes the poster's colour as the wide hero does; the title's hue is the fallback.

## Native title bar (Windows)

- The window keeps Windows' own title bar: its buttons, snap layouts and dragging all stay native. `NativeTitleBar(window)` in
  `desktopMain/.../shell/NativeTitleBar.kt` asks DWM (`dwmapi.dll`, through JNA) to draw it in dark mode, with the app's background
  as its colour and white text. Windows 10 gets the dark mode only; Windows 11 also gets the colours.
- The colour is fixed to the app's dark background. A light appearance would need the theme passed in.
- Other systems keep their own bar, untouched.

## Continue Watching, the source chooser, and the glass rule

- Continue Watching cards have no play overlay. A card opens the source chooser for its title or episode (`openSourceChooser`).
  Only a title with no IMDb id falls back to the details page. Cards are 33% larger in the wide layout (`WideScale.CARD`).
- The source chooser (wide) is two columns: the left two thirds show the episode (a large image from the original still, logo,
  MDBList ratings, synopsis); the right third lists the sources stacked, grouped by add-on. The phone layout keeps its sheet.
- The wide details page folds its poster column away as the page scrolls (like Home's hero).
- Glass rule (desktop): chips, source cards, the hero's Details button and the phone source rows are glass. Every glass surface
  falls back to flat only while the PC is on low battery (`PowerStatus.lowBattery`, read every 30 s by `BatteryWatch`: Windows
  through `GetSystemPowerStatus`, Linux through `/sys/class/power_supply`).

## Window memory (Batch 4.6c)

Code: `shared/.../shell/WindowMemory.kt` (the rules and the settings, common, unit tested), `desktopMain/.../shell/WindowMemoryTracker.kt` (restore at launch, the watcher), `desktopApp/.../Main.kt` (opens the window from the saved state and attaches the watcher). Keys: `desktop.window.*` in `PrefKeys` (desktop only; left out of a settings backup, since a window belongs to the machine). No iOS counterpart.

- **What is kept.** The mode (Windowed, Maximized or Full screen) and the floating window's place and size, in dp. A maximized or full-screen window keeps the floating geometry from before it was maximized, so un-maximizing after a restart returns to the size the user chose.
- **When it is written.** A move or a resize is written 400 ms after the last one (a Swing timer: restarting it is the debounce), and on the way out, before the settings are flushed (`AppExit`, which the window's close button runs).
- **How it opens.** The size is fitted inside the primary screen. A saved place is used only when the window's title bar, at its centre, lies on a screen; otherwise the window is centred (a monitor was unplugged, say). The mode is restored as it was, except that full screen is never restored: a window closed full screen is saved as the mode it was in before (maximized or windowed), and a state saved by an earlier version as full screen opens maximized. Full screen belongs to the player; a window that opened full screen would have no title bar and nothing to leave it by.
- **Sanity limits.** A saved size under 200 x 300 dp, or over 20 000, is dropped. The window's own minimum (360 x 600 px) is in pixels, so it is smaller in dp on a scaled screen; the limits catch garbage, not small windows.

### Notes
1. The phone-sized window toggle (Settings → Developer) changes the floating size, so it is remembered too. Leave the app in phone size and it opens in phone size.
2. Each screen is scaled by its own factor when its bounds are read. A window dragged between screens with different scaling can be a little off when it reopens; that is accepted.

### Check it
1. Move and resize the window, close Pear, reopen: same place and size.
2. Maximize, close, reopen: maximized. Restore it: it returns to the size from before the maximize.
3. Close in full screen (in the player): it opens as it was before full screen (maximized or windowed), not full screen. Pressing Escape or the mouse's back button in a full-screen window leaves full screen on any page, even with no player open (`ShellState.escape`).
4. Unplug a second monitor the window was on (or move the saved place off-screen by editing `settings.json`): the window opens centred on a screen.
5. Settings backup: export and import it; the window keys are not in the file.

### Not compiled
Run `gradle :shared:build` first (new common test: `WindowMemoryRulesTest`), then `gradle :desktopApp:run`. If it fails, check `rememberWindowState`'s parameter names and `WindowPosition.Absolute` first.

## Home glow (iOS parity)

- **Colour.** The glow is the average colour of the hero image the page draws (`heroAmbientFor`), tuned as iOS tunes it (`AmbientTone`): hue kept, saturation `min(1, s * 1.3 + 0.08)`, brightness held between 0.35 and 0.62. Nothing is guessed from the title. Until a hero's colour is known, the theme accent is used, as iOS does.
- **Placement.** Home draws the glow inside its scrolling content (`attachedAmbient`): a 1100 dp band behind the hero and the rows, so it moves with them. On the wide layout the band is behind the feed pane, not the sidebar, so the sidebar stays solid.
- **Fade.** The colour fades over 0.9 s on an ease-in-out curve, as iOS's `easeInOut(duration: 0.9)`.
- **Detail pages** keep the fixed glow behind the page (`AmbientWash`). They have no scrolling hero of this kind.
- **Not yet done:** the detail page still samples the poster (`DetailScreen.kt`, `rememberDominantColor`), so its glow can still differ from the hero image.

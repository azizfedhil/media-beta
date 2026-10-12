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

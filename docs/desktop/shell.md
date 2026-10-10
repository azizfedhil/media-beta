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

# Glass components (Batch 1.3)

Code: `shared/.../glass/` (components) and `preview/GlassGallery.kt` (the gallery screen). Uses Haze 2.0.1 (`haze`, `haze-blur`, `haze-glass`).

- `GlassScene(store, background = { ... }) { ... }`: the background is the blur source, the content above it holds glass surfaces. For the player, the video goes in `background`.
- `GlassSurface` is the base. It draws one of four styles from `GlassStore.mode`: **Glass** (default: Haze glass regular), **Frosted** (Haze blur, 20 dp, tint and noise), **Clear** (Haze glass clear), **Flat** (translucent tint, no blur). Outside a `GlassScene` it is Flat.
- Components: `GlassBar`, `GlassButton` (round or capsule, optional accent wash), `GlassPill` + `GlassPillButton`, `GlassCard`, `GlassMenu`/`GlassMenuItem`, `GlassSheet`.
- **Glass is neutral, as on iOS.** No glass surface takes a gradient. A glass surface carries at most a plain tint: the accent on a
  selected or active control (a tinted `GlassButton` at 55%, a selected pill or chip), or the neutral `PearColors.panel` wash on the
  sidebar, cards, bars, menus, sheets and the player's panels, so their text reads over a picture. Surfaces that float
  over content (cards, bars, buttons, menus, floating sheets) draw the theme outline (`border = true`), so they keep an edge over a black
  page. A pinned or selected row shows an icon or a check mark, not a coloured row.
- `GlassPerformance` maps to Haze's performance modes (Quality, Balanced, Performance).
- Haze's built-in glass picks its light or dark response from the OS appearance, not from the app's forced dark mode. On a light-mode Windows or Linux desktop the Glass and Clear styles may look lighter than intended. Frosted and Flat follow the app theme.
- Not measured yet: nobody has run blur over live video (Phase 0 gate skipped). Use the gallery's moving background and the Performance chips to compare, then the player in 4.3 for the real test.
- The module opts in to `@ExperimentalHazeApi` for every source set (`shared/build.gradle.kts`).
- Glass screens must keep scroll content *inside* the `hazeSource` (the `background` of `GlassScene`) and glass surfaces *outside* it, as `PageScaffold` does; a glass card inside the source would sample itself.

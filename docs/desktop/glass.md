# Glass components (Batch 1.3)

Code: `shared/.../glass/` (components) and `preview/GlassGallery.kt` (the gallery screen). Uses Haze 2.0.1 (`haze`, `haze-blur`, `haze-glass`).

- `GlassScene(store, background = { ... }) { ... }`: the background is the blur source, the content above it holds glass surfaces. For the player, the video goes in `background`.
- `GlassSurface` is the base. It draws one of four styles from `GlassStore.mode`: **Glass** (default: Haze glass regular), **Frosted** (Haze blur, 20 dp, tint and noise), **Clear** (Haze glass clear), **Flat** (translucent tint, no blur). Outside a `GlassScene` it is Flat.
- Components: `GlassBar`, `GlassButton` (round or capsule, optional accent wash), `GlassPill` + `GlassPillButton`, `GlassCard`, `GlassMenu`/`GlassMenuItem`, `GlassSheet`.
- **One glass, everywhere.** Every glass surface is drawn by `GlassSurface`, and they all look the same: no wash, no outline, no gradient, as iOS Liquid Glass. The only tint is the accent at 55% on a selected or active control (`wash`), as the Swift app does. The player's controls and panels use the same glass (the Blur look of `PlayerSurface`), so they match the app: there are no player-only washes. `PearColors.panel`, the old neutral wash of the sidebar, bars, menus and sheets, is gone. Cards (`GlassCard`) are not glass: a solid `PearColors.surface` (#1C1C1E), with no contour in any mode. A pinned or selected row shows an icon or a check mark, not a coloured row. The hover fill (`highlight`) fades in and out rather than snapping. The one rim is the player's: every player surface draws a 0.5 dp white rim at 50% (`PLAYER_RIM`, passed as `rim` to `GlassSurface`), as asked. Source cards and rows are flat fills, not glass, as on iOS, so no glass sits inside glass.
- `GlassPerformance` maps to Haze's performance modes (Quality, Balanced, Performance).
- Haze's built-in glass picks its light or dark response from the OS appearance, not from the app's forced dark mode. On a light-mode Windows or Linux desktop the Glass and Clear styles may look lighter than intended. Frosted and Flat follow the app theme.
- Not measured yet: nobody has run blur over live video (Phase 0 gate skipped). Use the gallery's moving background and the Performance chips to compare, then the player in 4.3 for the real test.
- The module opts in to `@ExperimentalHazeApi` for every source set (`shared/build.gradle.kts`).
- Glass screens must keep scroll content *inside* the `hazeSource` (the `background` of `GlassScene`) and glass surfaces *outside* it, as `PageScaffold` does; a glass card inside the source would sample itself.

- **The sidebar is solid, not glass.** It is `PearColors.sidebar` (#121212), as the iPad sidebar's `Color(white: 0.07)` is, so the ambient glow of Home does not tint it. No glass is drawn under it.

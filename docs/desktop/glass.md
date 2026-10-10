# Glass components (Batch 1.3)

Code: `shared/.../glass/` (components) and `preview/GlassGallery.kt` (the gallery screen). Uses Haze 2.0.1 (`haze`, `haze-blur`, `haze-glass`).

- `GlassScene(store, background = { ... }) { ... }`: the background is the blur source, the content above it holds glass surfaces. For the player, the video goes in `background`.
- `GlassSurface` is the base. It draws one of four styles from `GlassStore.mode`: **Frosted** (default: Haze blur, 20 dp, tint and noise), **Glass** (Haze glass regular), **Clear** (Haze glass clear), **Flat** (translucent tint, no blur). Outside a `GlassScene` it is Flat.
- Components: `GlassBar`, `GlassButton` (round or capsule, optional accent wash), `GlassPill` + `GlassPillButton`, `GlassCard`, `GlassMenu`/`GlassMenuItem`, `GlassSheet`.
- `GlassPerformance` maps to Haze's performance modes (Quality, Balanced, Performance).
- Haze's built-in glass picks its light or dark response from the OS appearance, not from the app's forced dark mode. On a light-mode Windows or Linux desktop the Glass and Clear styles may look lighter than intended. Frosted and Flat follow the app theme.
- Not measured yet: nobody has run blur over live video (Phase 0 gate skipped). Use the gallery's moving background and the Performance chips to compare, then the player in 4.3 for the real test.

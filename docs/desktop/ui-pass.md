# Desktop UI pass: decisions, changes and checks

Written at the end of the UI fix pass. It lists the decisions made, what changed, what is still open, and what to check on a real build. The source plan is `docs/desktop/` plus the fix brief from October 2026.

Nothing in this pass has been compiled or run in the environment that wrote it. Run `gradle :shared:allTests` and `gradle :desktopApp:run` first.

## Decisions (recorded)

- **Page colour** stays `#09090B`. The navy in the mockup is the poster's glow, not the page.
- **Details button** keeps the theme accent. Its fill covers its padding.
- **Sidebar** is solid (`PearColors.sidebar`, #121212), never tinted by the Home glow.
- **Nesting guard** is on: a glass surface inside another glass surface is flat, with no exemptions. To reverse it, remove the `LocalInsideGlass` provider in `GlassSurface`.
- **Player rim** removed: the player's surfaces have no outline, as you asked.
- **Home glow** follows iOS (the hero's own image, iOS's tuning, drawn in the scrolling content). It reaches further right on the wide layout (`AmbientTuning`).
- **Downloads** replace a stopped download when another source is chosen; an active or finished one blocks other sources.

## What changed

- **Glass:** one look everywhere (no wash, no outline, except the accent on active controls). The sidebar is solid. Flat sheets (`FlatSheet`) and flat buttons (`PearCircleButton`, `PearCapsuleButton`). Hover and press feedback on every interactive surface.
- **Home:** the glow samples the hero's image; it moves with the content; the avatar has a scrim on Home; the detail page reads its colour from the same image as its poster.
- **Player:** the veil (13% controls, 8% panels), panel widths that narrow to the window, the compact pill without the Fullscreen button, and scaling set by the window's shorter side. A 1080p fullscreen window keeps the desktop look.
- **Profile page:** wide layout with a header band, four stat tiles, and a 7:5 split; a narrow single column; a Switch profile action; Downloads on both layouts.
- **Settings:** a rail and one pane on wide; one column on the phone; switches, a segmented layout override, a stepper for the skip seconds, and a flat P2P confirmation.
- **Downloads:** per-source state, a link gate that matches iOS (plus manifests), manual redirects, a browser user agent, refusal of web pages and playlists, friendly HTTP errors, retries for transient failures, one stream per file, and refusal of very small files.

## Still open

- **Sidebar selection colour:** the selected row is white with black text in the code. iOS and the docs say accent. Not changed; say the word and it changes.
- **Veil and glow reach:** both are tuned from the plan's measurements, not from screenshots. Check them on a real build and tune the constants.
- **Small-file threshold:** a download under a quarter of the source's size is refused. The source's size is an estimate, so a badly estimated film could be refused.
- **Profile switch:** the Switch profile action opens the sheet from any page; confirm it reads well over the profile page.
- **Route audit at 320–430 dp:** not walked. Walk each route with the phone-sized window.
- **Haze:** the glass style is not checked against Haze's own options (refraction, specular). Look at it on a real build, in light and dark Windows appearance.

## Checks on a real build

1. `gradle :shared:allTests`, then `gradle :desktopApp:run`.
2. Home at 1280 and 1920 px wide: the glow matches the hero, reaches right, and has no vertical seam at the feed's edge. Scroll the phone layout: the glow moves with the hero.
3. Player at 1080p fullscreen: compare with the pre-pass screenshot. Nothing should change. Then the phone-sized window: the pill has no Fullscreen button, and panels fit.
4. Profile page at 1280 and 1920 px, and at 390 px: nothing overflows.
5. Settings at 1280 and 1920 px, and at 320–430 px: the rail and pane on wide, one column on the phone.
6. A source with a broken link: the row shows the friendly message, and a second source replaces it.
7. Glass in light and dark Windows appearance: the surfaces look the same as each other.

## Code-level items: done and skipped

Done: the player rim (removed everywhere, including the info card and the page card), `ContentCard` with a deprecated `GlassCard` alias, `GlassTuning`, the nesting-warning switch, the sidebar hairline, `SwatchGrid` in the profile editor, a stronger scrim while a synopsis is open, prefetching the glow colour for every hero page, refusing non-video extensions, the Glass gallery's Kit section, and four download-manager tests.

Skipped on purpose:
- **The glow's fade height tied to the hero.** iOS draws a fixed 1100 pt band, and the desktop follows iOS. It is one number in `AmbientColor.kt` if you want it.
- **`GlassEdge`.** It was for the rim, which is gone, so the sidebar needs no edge setting.
- **Region-based glow tests.** The glow averages the whole image, as iOS does; there is no region to test.

# Brand: intro, wordmark, profile on wide windows

Code: `shared/.../brand/` (`PearOutline.kt`, `PearGeometry.kt` and `PearBrand.kt` are pure and unit tested; `PearAnimation.kt` draws them). Spec: `PearAnimationView.swift`, `PearGeometry.swift`, `PearLogo.swift`, and the profile views in `ProfileViews.swift` / `WideLayout.swift`.

## The "pear." intro

- `PearLaunchOverlay()` (in `App()`, over the shell): the intro on the splash colour (#0B0B0D), then a 350 ms beat, then a 350 ms fade. Clicks are swallowed while it plays. The shell loads underneath.
- The intro is the Swift timeline: 3940 ms of motion at 2x, so about 2 s on screen. It rests on the wordmark.
- Time comes from the frame clock (`withFrameMillis`). The animation redraws the canvas only, not the composition.
- The geometry is built on `Dispatchers.Default` before the first frame. It runs once per process (about 30 ms on a desktop).
- `PearAnimation(mode, speed, onFinish)` also takes `PearMode.Loader` (the Swift loop for buffering). Nothing uses it yet.
- Reduced motion is not implemented: there is no OS setting read on desktop yet.

## The wordmark

- `PearWordmark(height)`: the typed "pear." as vectors, with the glyph paths copied from the Swift file. The letters follow the appearance (cream on dark, ink on light); the full stop is always the brand violet (#7C6CFF).
- Sidebar: under the profile row and the clock, at 24 dp high. The icon-only sidebar has no room, so it shows none.

## Profile on wide windows

- **"Who's watching?" (`ProfileSheetOverlay`)**: in the wide layout it opens as a floating 380 dp card just right of the sidebar, under its avatar, with all corners rounded and no grabber. Avatars are 56 dp there, so five items fit. A tap outside closes it. The phone layout keeps the bottom sheet.
- **Profile page (`ProfileDetailScreen`)**: capped at 1120 dp and centred. At 760 dp and wider it uses two columns: identity, integrations, library and the summary tiles on the left; watch time, taste and "When you watch" on the right. Narrower content keeps one column, the phone order.
- The iOS detail page is a single scrolling column. The two columns are a desktop choice for wide windows, not a port of the iPad layout.

## Check it

1. Start the app: the black screen shows "pear." drawing itself (monogram, face, wink, wordmark), then fades to Home.
2. Wide window (over 640 dp): the wordmark sits under the avatar and the clock. Fold the sidebar on Home by scrolling: the wordmark disappears with the labels.
3. Click the avatar on a wide window: the card opens beside the sidebar. Click outside: it closes. Add more profiles than fit: the row scrolls.
4. Open the profile page at about 1400 dp: two columns, capped width. At about 800 dp: two columns. At 700 dp in the wide layout: one column.

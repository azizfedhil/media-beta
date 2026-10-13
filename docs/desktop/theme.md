# Theme, type and icons (Batch 1.2)

Code: `shared/.../theme/`. Preview screen: `preview/ThemePreview.kt` (Settings -> Developer -> Theme preview).

- `PearTheme(store) { ... }` provides colours and type; read them with `PearTheme.colors` and `PearTheme.type`.
- **Accent** (`Accent.kt`): the 8 presets and the `system` neutral accent from `Theme` in MediaHubApp.swift. `ThemeStore.accentHex` holds the same
  value as the iOS `ui.accent` key (`"#RRGGBB"` or `"system"`), so Batch 2.1 persists it unchanged. `onAccent` is black or white by WCAG
  luminance, `accent2` is the hue-shifted neighbour used for gradients. Same arithmetic as Swift; covered by `AccentTest`.
- **Appearance**: Dark by default (Swift forces dark), Light and System are available. Palettes follow iOS dark and light system colours.
- **Colours** (`PearColors`): `background`, `surface` (glass and panel base), `sidebar`, `fillSubtle`/`fill`/`fillStrong` (white at 8/12/18% in dark),
  `outline`, `hairline`, four text levels, five status colours.
- **Tokens** (`PearTokens.kt`): `PearRadii` (6/12/14/16/22/28/pill, taken from the radii the Swift code uses most), `PearSpacing`, `PearMotion`
  (durations and the iOS push curve).
- **Gradients**: the only gradient is `PearColors.gradient` (the accent into its neighbouring hue), used for progress fills and the profile
  stat card, as on iOS. Glass has none. The accent layers that used to sit under glass (`sheen`, `menu`, `card`, `button`) are gone.
- **Cards** (`GlassCard`) are the `surface` colour, #1C1C1E, the iOS dark cell colour, with no contour. Glass surfaces use `panel` as their neutral wash.
- **Type** (`PearType.kt`): the iOS text styles at default size (largeTitle 34/41 ... caption2 11/13). Face is Inter if its files are present in
  `shared/src/desktopMain/resources/fonts/` (see the README there), otherwise the system font.
- **Icons** (`PearIcon.kt`): `PearIcon("house.fill")` takes the SF Symbol names the Swift app uses. Unknown names show a question mark.

## Icon set: Lucide

The plan said Material Symbols. The icons are now **Lucide** (ISC licence, a fork of Feather, MIT), version 1.55.0 for the
generated file. Lucide is outline-only: every element is a 2-unit stroke with round caps and joins on a 24x24 grid.
`tools/gen_icons.py` turns each element (path, circle, ellipse, rect, line, polyline, polygon) into path data and keeps
whether it is filled (the few solid dots). `PearIcon` strokes the outline parts and fills the dots, so no font file or
resource pipeline is needed.

- The map is `SF_TO_LUCIDE` in `tools/gen_icons.py`. To add an icon, add a line there and run
  `python tools/gen_icons.py <folder with Lucide *.svg>`, then commit the regenerated `PearIconData.kt`.
- The script stops with a message if a mapped name is missing from the folder, or if an icon's stroke width is not 2.
- Filled SF variants (`checkmark.circle.fill`, `play.circle.fill`) draw as the Lucide outline: Lucide has no solid set.
  A few Lucide icons have solid dots; those stay filled.
- The About screen credits Lucide (ISC) with Feather (MIT).

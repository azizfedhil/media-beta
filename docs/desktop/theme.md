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
- **Type** (`PearType.kt`): the iOS text styles at default size (largeTitle 34/41 ... caption2 11/13). Face is Inter if its files are present in
  `shared/src/desktopMain/resources/fonts/` (see the README there), otherwise the system font.
- **Icons** (`PearIcon.kt`): `PearIcon("house.fill")` takes the SF Symbol names the Swift app uses. Unknown names show a question mark.

## Deviation from the plan: icon set

The plan said Material Symbols. I could not download it (no network here), so the icons are **Material Design Icons** (Pictogrammers, Apache 2.0, the same
licence). They are stored as SVG path data in `theme/icons/PearIconData.kt` and drawn with Compose's `PathParser`: no font file, no resource pipeline.
The map is `SF_TO_MDI` in `tools/gen_icons.py`; to add an icon, add a line there and run
`python tools/gen_icons.py <path to @mdi/svg/svg> `. MDI is added to the About screen list in Batch 3.5. Switching to Material Symbols later only changes
`PearIconData.kt` and `PearIcon`.

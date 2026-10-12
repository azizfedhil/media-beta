#!/usr/bin/env python3
"""Generates shared/.../theme/icons/PearIconData.kt: the SF Symbol -> open icon map and the vector paths.

Usage:  python tools/gen_icons.py <dir with Material Design Icons *.svg> [output.kt]

Icon set: Material Design Icons by Pictogrammers (Apache 2.0), https://pictogrammers.com/library/mdi/
(npm package @mdi/svg, folder svg/). Every icon is a single path on a 24x24 grid, so it is stored as an SVG
path string and drawn by PearIcon with Compose's PathParser. No font file and no resource pipeline needed.

To add an icon: add a line to SF_TO_MDI, run this script, commit the regenerated file.
"""
import re
import sys
from pathlib import Path

# SF Symbol name used by the Swift app -> MDI icon name.
SF_TO_MDI = {
    # navigation / tabs
    "house.fill": "home",
    "safari": "compass-outline",
    "safari.fill": "compass",
    "books.vertical": "bookshelf",
    "books.vertical.fill": "bookshelf",
    "gearshape": "cog-outline",
    "gearshape.fill": "cog",
    "magnifyingglass": "magnify",
    # arrows and chevrons
    "chevron.left": "chevron-left",
    "chevron.right": "chevron-right",
    "chevron.up": "chevron-up",
    "chevron.down": "chevron-down",
    "chevron.up.chevron.down": "unfold-more-horizontal",
    "arrow.left": "arrow-left",
    "arrow.right": "arrow-right",
    "arrow.up": "arrow-up",
    "arrow.down": "arrow-down",
    "arrow.clockwise": "refresh",
    "arrow.triangle.2.circlepath": "sync",
    "arrow.up.arrow.down": "swap-vertical",
    "arrow.up.right.square": "open-in-new",
    "arrow.right.circle": "arrow-right-circle-outline",
    "arrow.down.circle": "arrow-down-circle-outline",
    "arrow.down.circle.fill": "arrow-down-circle",
    # playback
    "play.fill": "play",
    "play.circle": "play-circle-outline",
    "play.circle.fill": "play-circle",
    "play.slash": "play-box-outline",
    "pause.fill": "pause",
    "pause.circle": "pause-circle-outline",
    "forward.fill": "fast-forward",
    "forward.end.fill": "skip-next",
    "backward.fill": "rewind",
    "goforward": "rotate-right",
    "gobackward": "rotate-left",
    "gobackward.10": "rewind-10",
    "goforward.10": "fast-forward-10",
    "hare.fill": "rabbit",
    "tortoise.fill": "turtle",
    "waveform": "waveform",
    "captions.bubble": "subtitles-outline",
    "speaker.wave.3.fill": "volume-high",
    "speaker.wave.2.fill": "volume-medium",
    "speaker.slash.fill": "volume-off",
    "pip": "picture-in-picture-bottom-right",
    "arrow.up.left.and.arrow.down.right": "fullscreen",
    "arrow.down.right.and.arrow.up.left": "fullscreen-exit",
    "airplayvideo": "cast",
    "gauge": "speedometer",
    # media / content
    "film": "filmstrip",
    "film.stack": "filmstrip-box-multiple",
    "tv": "television",
    "theatermasks.fill": "drama-masks",
    "square.stack.3d.up.fill": "layers",
    "square.stack.fill": "card-multiple",
    "square.grid.2x2.fill": "view-grid",
    "list.bullet": "format-list-bulleted",
    "list.star": "playlist-star",
    "star.fill": "star",
    "flame.fill": "fire",
    "sparkles": "creation",
    "puzzlepiece.extension.fill": "puzzle",
    "seal.fill": "check-decagram",
    "heart": "heart-outline",
    "heart.fill": "heart",
    # status
    "checkmark": "check",
    "checkmark.circle": "check-circle-outline",
    "checkmark.circle.fill": "check-circle",
    "xmark": "close",
    "xmark.circle": "close-circle-outline",
    "xmark.circle.fill": "close-circle",
    "xmark.octagon.fill": "close-octagon",
    "exclamationmark.circle.fill": "alert-circle",
    "exclamationmark.triangle.fill": "alert",
    "info.circle": "information-outline",
    "info.circle.fill": "information",
    "minus.circle": "minus-circle-outline",
    "plus": "plus",
    "plus.circle": "plus-circle-outline",
    "circle": "circle-outline",
    "circle.dashed": "dots-circle",
    "nosign": "cancel",
    "lock.fill": "lock",
    # connectivity
    "wifi": "wifi",
    "wifi.slash": "wifi-off",
    "antenna.radiowaves.left.and.right": "broadcast",
    "antenna.radiowaves.left.and.right.slash": "broadcast-off",
    "globe": "earth",
    # actions
    "trash": "trash-can-outline",
    "pencil": "pencil",
    "ellipsis": "dots-horizontal",
    "ellipsis.circle": "dots-horizontal-circle-outline",
    "square.and.arrow.up": "export-variant",
    "square.and.arrow.down": "download",
    "doc.on.doc": "content-copy",
    "doc.on.clipboard": "clipboard-outline",
    "link": "link-variant",
    "pin": "pin-outline",
    "pin.fill": "pin",
    "pin.slash": "pin-off-outline",
    "bookmark": "bookmark-outline",
    "bookmark.fill": "bookmark",
    "bookmark.slash": "bookmark-off-outline",
    "eye": "eye-outline",
    "eye.slash": "eye-off-outline",
    "slider.horizontal.3": "tune-variant",
    "line.3.horizontal.decrease.circle.fill": "filter",
    "textformat.size.larger": "format-font-size-increase",
    "textformat.size.smaller": "format-font-size-decrease",
    # time / weather / people
    "calendar": "calendar-blank-outline",
    "clock": "clock-outline",
    "clock.fill": "clock",
    "clock.badge.fill": "clock-alert",
    "sun.max.fill": "white-balance-sunny",
    "sunrise.fill": "weather-sunset-up",
    "sunset.fill": "weather-sunset-down",
    "moon.stars.fill": "weather-night",
    "person.fill": "account",
    "person.crop.circle": "account-circle-outline",
}
FALLBACK = "help-circle-outline"  # shown for any SF name that is not in the map

HEADER = '''// GENERATED by tools/gen_icons.py. Do not edit by hand: change SF_TO_MDI in the script and regenerate.
//
// Icons: Material Design Icons by Pictogrammers, Apache License 2.0 (https://pictogrammers.com/library/mdi/).
// Listed in the About screen with the other third-party components.
package com.mediahub.pear.theme.icons

/** SF Symbol name (as used in the Swift app) -> MDI icon name. */
internal val sfToMdi: Map<String, String> = mapOf(
'''


def read_path(svg_dir: Path, name: str) -> str:
    text = (svg_dir / f"{name}.svg").read_text(encoding="utf-8")
    if 'viewBox="0 0 24 24"' not in text:
        raise SystemExit(f"{name}: expected a 24x24 viewBox")
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', text)
    if len(paths) != 1:
        raise SystemExit(f"{name}: expected exactly one <path>, found {len(paths)}")
    return paths[0]


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    svg_dir = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(
        "shared/src/commonMain/kotlin/com/mediahub/pear/theme/icons/PearIconData.kt")
    mdi_names = sorted(set(SF_TO_MDI.values()) | {FALLBACK})
    lines = [HEADER]
    for sf in sorted(SF_TO_MDI):
        lines.append(f'    "{sf}" to "{SF_TO_MDI[sf]}",\n')
    lines.append(")\n\n")
    lines.append(f'internal const val FALLBACK_ICON = "{FALLBACK}"\n\n')
    lines.append("/** MDI icon name -> SVG path data on a 24x24 grid. */\n")
    lines.append("internal val mdiPaths: Map<String, String> = mapOf(\n")
    for name in mdi_names:
        lines.append(f'    "{name}" to "{read_path(svg_dir, name)}",\n')
    lines.append(")\n")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(lines), encoding="utf-8")
    print(f"wrote {out}: {len(SF_TO_MDI)} symbols, {len(mdi_names)} paths")


if __name__ == "__main__":
    main()

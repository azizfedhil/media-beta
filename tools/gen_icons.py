#!/usr/bin/env python3
"""Generates shared/.../theme/icons/PearIconData.kt: the SF Symbol -> Lucide map, and the drawing data of each icon.

Usage:  python tools/gen_icons.py <folder with Lucide *.svg> [output.kt]

Icon set: Lucide (ISC licence, https://lucide.dev), a fork of Feather (MIT). Its icons are outlines: every element is a
2-unit stroke with round caps and joins on a 24x24 grid. The generator turns each element (path, circle, ellipse, rect,
line, polyline, polygon) into path data, and keeps whether it is filled (the few solid dots) or stroked. PearIcon draws
them with Compose's PathParser, so no font file and no resource pipeline are needed.

To add an icon: add a line to SF_TO_LUCIDE, run this script, commit the regenerated file.
"""
import re
import sys
from pathlib import Path

# SF Symbol name used by the Swift app -> Lucide icon name (file name without .svg).
SF_TO_LUCIDE = {
    "airplayvideo": "airplay",
    "antenna.radiowaves.left.and.right": "radio-tower",
    "antenna.radiowaves.left.and.right.slash": "radio-off",
    "arrow.clockwise": "rotate-cw",
    "arrow.down": "arrow-down",
    "arrow.down.circle": "circle-arrow-down",
    "arrow.down.circle.fill": "circle-arrow-down",
    "arrow.down.right.and.arrow.up.left": "minimize",
    "arrow.left": "arrow-left",
    "arrow.right": "arrow-right",
    "arrow.right.circle": "circle-arrow-right",
    "arrow.triangle.2.circlepath": "refresh-cw",
    "arrow.up": "arrow-up",
    "arrow.up.arrow.down": "arrow-up-down",
    "arrow.up.left.and.arrow.down.right": "maximize",
    "arrow.up.right.square": "square-arrow-out-up-right",
    "backward.fill": "rewind",
    "bookmark": "bookmark",
    "bookmark.fill": "bookmark",
    "bookmark.slash": "bookmark-x",
    "books.vertical": "library",
    "books.vertical.fill": "library",
    "calendar": "calendar",
    "captions.bubble": "captions",
    "checkmark": "check",
    "checkmark.circle": "circle-check",
    "checkmark.circle.fill": "circle-check",
    "chevron.down": "chevron-down",
    "chevron.left": "chevron-left",
    "chevron.right": "chevron-right",
    "chevron.up": "chevron-up",
    "chevron.up.chevron.down": "chevrons-up-down",
    "circle": "circle",
    "circle.dashed": "circle-dashed",
    "clock": "clock",
    "clock.badge.fill": "clock-alert",
    "clock.fill": "clock",
    "doc.on.clipboard": "clipboard",
    "doc.on.doc": "copy",
    "ellipsis": "ellipsis",
    "ellipsis.circle": "circle-ellipsis",
    "exclamationmark.circle.fill": "circle-alert",
    "exclamationmark.triangle.fill": "triangle-alert",
    "eye": "eye",
    "eye.fill": "eye",
    "eye.slash": "eye-off",
    "film": "film",
    "film.stack": "clapperboard",
    "flame.fill": "flame",
    "forward.end.fill": "skip-forward",
    "forward.fill": "fast-forward",
    "gauge": "gauge",
    "gearshape": "settings",
    "gearshape.fill": "settings",
    "globe": "globe",
    "gobackward": "rotate-ccw",
    "gobackward.10": "rotate-ccw",
    "goforward": "rotate-cw",
    "goforward.10": "rotate-cw",
    "hare.fill": "rabbit",
    "heart": "heart",
    "heart.fill": "heart",
    "house.fill": "house",
    "info.circle": "info",
    "info.circle.fill": "info",
    "line.3.horizontal.decrease.circle.fill": "list-filter",
    "link": "link",
    "list.bullet": "list",
    "list.star": "list-todo",
    "lock.fill": "lock",
    "magnifyingglass": "search",
    "minus.circle": "circle-minus",
    "moon.stars.fill": "moon-star",
    "nosign": "ban",
    "pause.circle": "circle-pause",
    "pause.fill": "pause",
    "pawprint.fill": "paw-print",
    "pencil": "pencil",
    "person.crop.circle": "circle-user",
    "person.fill": "user",
    "pin": "pin",
    "pin.fill": "pin",
    "pin.slash": "pin-off",
    "pip": "picture-in-picture-2",
    "pip.enter": "picture-in-picture-2",
    "play.circle": "circle-play",
    "play.circle.fill": "circle-play",
    "play.fill": "play",
    "play.rectangle.fill": "square-play",
    "play.slash": "square-play",
    "playpause.fill": "circle-play",
    "plus": "plus",
    "plus.circle": "circle-plus",
    "puzzlepiece.extension.fill": "puzzle",
    "rectangle.stack": "layers-2",
    "safari": "compass",
    "safari.fill": "compass",
    "seal.fill": "badge-check",
    "server.rack": "server",
    "slider.horizontal.3": "sliders-horizontal",
    "sparkles": "sparkles",
    "speaker.slash": "volume-off",
    "speaker.slash.fill": "volume-off",
    "speaker.wave.2": "volume-1",
    "speaker.wave.2.fill": "volume-1",
    "speaker.wave.3.fill": "volume-2",
    "speedometer": "gauge",
    "square.and.arrow.down": "download",
    "square.and.arrow.up": "share",
    "square.grid.2x2.fill": "layout-grid",
    "square.stack.3d.up.fill": "layers",
    "square.stack.fill": "layers",
    "star.fill": "star",
    "sun.max.fill": "sun",
    "sunrise.fill": "sunrise",
    "sunset.fill": "sunset",
    "textformat.size.larger": "a-arrow-up",
    "textformat.size.smaller": "a-arrow-down",
    "theatermasks.fill": "drama",
    "tortoise.fill": "turtle",
    "trash": "trash",
    "tv": "tv",
    "waveform": "audio-waveform",
    "wifi": "wifi",
    "wifi.slash": "wifi-off",
    "xmark": "x",
    "xmark.circle": "circle-x",
    "xmark.circle.fill": "circle-x",
    "xmark.octagon.fill": "octagon-x",
}

FALLBACK = "circle-question-mark"  # shown for any SF name that is not in the map
STROKE_WIDTH = "2"                 # every Lucide icon is drawn with this stroke width on the 24-unit grid

HEADER = """// GENERATED by tools/gen_icons.py. Do not edit by hand: change SF_TO_LUCIDE in the script and regenerate.
//
// Icons: Lucide, ISC licence (https://lucide.dev), a fork of Feather (MIT, Cole Bemis). Listed in the About screen.
package com.mediahub.pear.theme.icons

"""

ELEMENT = re.compile(r"<(path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*)>")
ATTR = re.compile(r'([a-zA-Z-]+)="([^"]*)"')


def num(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def element_path(tag: str, a: dict) -> str:
    def f(key, default=0.0):
        return float(a.get(key, default))

    if tag == "path":
        return a["d"]
    if tag in ("circle", "ellipse"):
        cx, cy = f("cx"), f("cy")
        rx = f("r") if tag == "circle" else f("rx")
        ry = f("r") if tag == "circle" else f("ry")
        return (f"M{num(cx - rx)} {num(cy)} A{num(rx)} {num(ry)} 0 1 0 {num(cx + rx)} {num(cy)} "
                f"A{num(rx)} {num(ry)} 0 1 0 {num(cx - rx)} {num(cy)} Z")
    if tag == "rect":
        x, y, w, h = f("x"), f("y"), f("width"), f("height")
        rx = f("rx", f("ry", 0.0))
        ry = f("ry", rx)
        if rx <= 0 or ry <= 0:
            return f"M{num(x)} {num(y)} H{num(x + w)} V{num(y + h)} H{num(x)} Z"
        return (f"M{num(x + rx)} {num(y)} H{num(x + w - rx)} A{num(rx)} {num(ry)} 0 0 1 {num(x + w)} {num(y + ry)} "
                f"V{num(y + h - ry)} A{num(rx)} {num(ry)} 0 0 1 {num(x + w - rx)} {num(y + h)} "
                f"H{num(x + rx)} A{num(rx)} {num(ry)} 0 0 1 {num(x)} {num(y + h - ry)} "
                f"V{num(y + ry)} A{num(rx)} {num(ry)} 0 0 1 {num(x + rx)} {num(y)} Z")
    if tag == "line":
        return f"M{num(f('x1'))} {num(f('y1'))} L{num(f('x2'))} {num(f('y2'))}"
    if tag in ("polyline", "polygon"):
        pts = [float(v) for v in re.split(r"[\s,]+", a["points"].strip()) if v]
        pairs = [(pts[i], pts[i + 1]) for i in range(0, len(pts) - 1, 2)]
        body = "M" + " L".join(f"{num(x)} {num(y)}" for x, y in pairs)
        return body + (" Z" if tag == "polygon" else "")
    raise SystemExit(f"unsupported element <{tag}>")


def read_icon(icon_dir: Path, name: str) -> list:
    path = icon_dir / f"{name}.svg"
    if not path.exists():
        raise SystemExit(f"{name}: no file {path}")
    text = path.read_text(encoding="utf-8")
    root = re.search(r"<svg[^>]*>", text, re.S).group(0)
    if 'viewBox="0 0 24 24"' not in root:
        raise SystemExit(f"{name}: expected a 24x24 viewBox")
    if f'stroke-width="{STROKE_WIDTH}"' not in root:
        raise SystemExit(f"{name}: expected stroke-width {STROKE_WIDTH}")
    shapes = []
    for tag, attrs in ELEMENT.findall(text):
        a = dict(ATTR.findall(attrs))
        filled = a.get("fill", "none") == "currentColor"
        shapes.append((element_path(tag, a), filled))
    if not shapes:
        raise SystemExit(f"{name}: no drawing elements")
    return shapes


def kotlin_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    icon_dir = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(
        "shared/src/commonMain/kotlin/com/mediahub/pear/theme/icons/PearIconData.kt")
    names = sorted(set(SF_TO_LUCIDE.values()) | {FALLBACK})
    icons = {name: read_icon(icon_dir, name) for name in names}

    lines = [HEADER]
    lines.append("/** SF Symbol name (as used in the Swift app) -> Lucide icon name. */\n")
    lines.append("internal val sfToLucide: Map<String, String> = mapOf(\n")
    for sf in sorted(SF_TO_LUCIDE):
        lines.append(f'    "{sf}" to "{SF_TO_LUCIDE[sf]}",\n')
    lines.append(")\n\n")
    lines.append(f'internal const val FALLBACK_ICON = "{FALLBACK}"\n\n')
    lines.append("/** One part of an icon: its path data on the 24x24 grid, drawn filled, or stroked at 2 units. */\n")
    lines.append("internal class LucideShape(val d: String, val filled: Boolean)\n\n")
    lines.append("private fun stroked(d: String) = LucideShape(d, filled = false)\n")
    lines.append("private fun filled(d: String) = LucideShape(d, filled = true)\n\n")
    lines.append("/** Lucide icon name -> its parts. */\n")
    lines.append("internal val lucideIcons: Map<String, List<LucideShape>> = mapOf(\n")
    for name in names:
        parts = ", ".join(
            f'{"filled" if filled else "stroked"}("{kotlin_string(d)}")' for d, filled in icons[name])
        lines.append(f'    "{name}" to listOf({parts}),\n')
    lines.append(")\n")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(lines), encoding="utf-8")
    print(f"wrote {out}: {len(SF_TO_LUCIDE)} symbols, {len(names)} icons")


if __name__ == "__main__":
    main()

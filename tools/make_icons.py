#!/usr/bin/env python3
"""Make Pear's icon files from one square PNG of 1024 px or more (the iOS AppIcon works).

Writes:
  desktopApp/packaging/icons/pear.ico     16, 24, 32, 48, 64, 128, 256 px: the exe, the installer and the shortcuts
  desktopApp/src/main/resources/pear.png  512 px: the window's title bar and taskbar icon, and the Linux launcher

    python3 tools/make_icons.py path/to/AppIcon-1024.png

Requires Pillow (pip install pillow).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
PNG_SIZE = 512
MIN_SOURCE = 1024

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ICO = REPO_ROOT / "desktopApp" / "packaging" / "icons" / "pear.ico"
DEFAULT_PNG = REPO_ROOT / "desktopApp" / "src" / "main" / "resources" / "pear.png"


def make(source: Path, ico_out: Path, png_out: Path, allow_small: bool = False) -> None:
    with Image.open(source) as opened:
        if opened.width != opened.height:
            raise SystemExit(f"{source} is {opened.width}x{opened.height}: the icon must be square.")
        if opened.width < MIN_SOURCE and not allow_small:
            raise SystemExit(
                f"{source} is {opened.width} px: use at least {MIN_SOURCE} px so every size is a clean downscale."
            )
        image = opened.convert("RGBA")

    ico_out.parent.mkdir(parents=True, exist_ok=True)
    png_out.parent.mkdir(parents=True, exist_ok=True)
    image.save(ico_out, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    image.resize((PNG_SIZE, PNG_SIZE), Image.LANCZOS).save(png_out, format="PNG")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("source", type=Path, help="square PNG, 1024 px or larger")
    parser.add_argument("--ico-out", type=Path, default=DEFAULT_ICO)
    parser.add_argument("--png-out", type=Path, default=DEFAULT_PNG)
    parser.add_argument("--allow-small", action="store_true", help="accept a source smaller than 1024 px")
    args = parser.parse_args(argv)
    make(args.source, args.ico_out, args.png_out, args.allow_small)
    print(f"wrote {args.ico_out} ({', '.join(str(s) for s in ICO_SIZES)} px)")
    print(f"wrote {args.png_out} ({PNG_SIZE} px)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Tests for tools/make_icons.py. Run from the repo root: python3 -m unittest discover -s tools/tests"""

import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import make_icons  # noqa: E402


def synthetic_source(path: Path, size: int) -> None:
    """A plain test mark (not Pear's artwork): a rounded square on a transparent background."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([size // 8, size // 8, size - size // 8, size - size // 8],
                                          radius=size // 5, fill=(200, 60, 40, 255))
    img.save(path)


class MakeIconsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.dir = Path(self._dir.name)

    def tearDown(self) -> None:
        self._dir.cleanup()

    def test_ico_has_exactly_the_planned_sizes(self) -> None:
        src = self.dir / "src.png"
        synthetic_source(src, 1024)
        ico, png = self.dir / "out" / "pear.ico", self.dir / "out" / "pear.png"
        make_icons.make(src, ico, png)

        with Image.open(ico) as icon:
            self.assertEqual(sorted(icon.info["sizes"]), sorted((s, s) for s in make_icons.ICO_SIZES))

    def test_png_is_512_px_and_keeps_transparency(self) -> None:
        src = self.dir / "src.png"
        synthetic_source(src, 1024)
        png = self.dir / "pear.png"
        make_icons.make(src, self.dir / "pear.ico", png)

        with Image.open(png) as out:
            self.assertEqual(out.size, (512, 512))
            self.assertEqual(out.mode, "RGBA")
            self.assertEqual(out.getpixel((0, 0))[3], 0)  # corner stays transparent

    def test_rejects_a_non_square_source(self) -> None:
        src = self.dir / "wide.png"
        Image.new("RGBA", (1024, 512)).save(src)
        with self.assertRaises(SystemExit):
            make_icons.make(src, self.dir / "a.ico", self.dir / "a.png")

    def test_rejects_a_source_below_1024_unless_allowed(self) -> None:
        src = self.dir / "small.png"
        synthetic_source(src, 256)
        with self.assertRaises(SystemExit):
            make_icons.make(src, self.dir / "a.ico", self.dir / "a.png")
        make_icons.make(src, self.dir / "b.ico", self.dir / "b.png", allow_small=True)
        self.assertTrue((self.dir / "b.ico").is_file())


if __name__ == "__main__":
    unittest.main()

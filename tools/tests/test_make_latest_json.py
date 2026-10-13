"""Tests for tools/make_latest_json.py. Run from the repo root: python3 -m unittest discover -s tools/tests"""

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import make_latest_json as tool  # noqa: E402


class MakeLatestJsonTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.dir = Path(self._dir.name)
        self.setup_exe = self.dir / "PearSetup.exe"
        self.setup_exe.write_bytes(b"installer bytes")
        self.rpm = self.dir / "pear-0.0.1-1.x86_64.rpm"
        self.rpm.write_bytes(b"rpm bytes")

    def tearDown(self) -> None:
        self._dir.cleanup()

    def test_windows_entry_has_url_hash_and_size(self) -> None:
        m = tool.build("0.0.1-Alpha", "desktop-v0.0.1-Alpha", "owner/pear", self.setup_exe, None, "2026-10-13T00:00:00Z")
        entry = m["platforms"]["windows-x64"]
        self.assertEqual(entry["file"], "PearSetup.exe")
        self.assertEqual(entry["url"], "https://github.com/owner/pear/releases/download/desktop-v0.0.1-Alpha/PearSetup.exe")
        self.assertEqual(entry["sha256"], hashlib.sha256(b"installer bytes").hexdigest())
        self.assertEqual(entry["size"], len(b"installer bytes"))

    def test_both_platforms_and_release_page(self) -> None:
        m = tool.build("0.0.1-Alpha", "desktop-v0.0.1-Alpha", "owner/pear", self.setup_exe, self.rpm, "t")
        self.assertEqual(set(m["platforms"]), {"windows-x64", "linux-x64-rpm"})
        self.assertEqual(m["releaseUrl"], "https://github.com/owner/pear/releases/tag/desktop-v0.0.1-Alpha")
        self.assertEqual(m["version"], "0.0.1-Alpha")
        self.assertEqual(m["schema"], 1)

    def test_main_writes_valid_json_to_out(self) -> None:
        out = self.dir / "dist" / "latest.json"
        code = tool.main([
            "--version", "0.0.1-Alpha", "--tag", "desktop-v0.0.1-Alpha", "--repo", "owner/pear",
            "--windows", str(self.setup_exe), "--out", str(out), "--published", "2026-10-13T00:00:00Z",
        ])
        self.assertEqual(code, 0)
        data = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(data["published"], "2026-10-13T00:00:00Z")
        self.assertIn("windows-x64", data["platforms"])

    def test_refuses_to_write_an_empty_manifest(self) -> None:
        with self.assertRaises(SystemExit):
            tool.build("0.0.1-Alpha", "t", "o/p", None, None, "t")


if __name__ == "__main__":
    unittest.main()

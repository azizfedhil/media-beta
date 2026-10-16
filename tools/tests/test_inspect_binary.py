"""Tests for tools/inspect_binary.py. Run from the repo root: python3 -m unittest discover -s tools/tests"""

import contextlib
import hashlib
import io
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import inspect_binary as tool  # noqa: E402


def fake_pe(machine: int = 0x8664, dll: bool = True) -> bytes:
    """The smallest bytes that the inspector reads as a PE file: DOS header, PE signature, COFF header."""
    e_lfanew = 0x80
    buf = bytearray(e_lfanew + 4 + 20)
    buf[0:2] = b"MZ"
    struct.pack_into("<I", buf, 0x3C, e_lfanew)
    buf[e_lfanew : e_lfanew + 4] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", buf, e_lfanew + 4, machine, 0, 0, 0, 0, 0, 0x2102 if dll else 0x0102)
    return bytes(buf)


class InspectLibmpvTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.dir = Path(self._dir.name)

    def tearDown(self) -> None:
        self._dir.cleanup()

    def write(self, name: str, data: bytes) -> Path:
        path = self.dir / name
        path.write_bytes(data)
        return path

    def run_tool(self, path: Path, expect: str | None = None) -> tuple[int, str]:
        argv = [str(path)] + (["--expect-sha256", expect] if expect else [])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = tool.main(argv)
        return code, out.getvalue()

    def test_bare_x64_dll_is_identified_by_header_not_name(self) -> None:
        path = self.write("libmpv-2.dll", fake_pe(0x8664, dll=True))
        code, text = self.run_tool(path)
        self.assertEqual(code, 0)
        self.assertIn("Windows DLL, x64", text)

    def test_x86_dll_is_reported_as_32_bit(self) -> None:
        path = self.write("libmpv-2.dll", fake_pe(0x014C, dll=True))
        _, text = self.run_tool(path)
        self.assertIn("Windows DLL, x86", text)

    def test_zip_lists_members_and_reports_dlls_inside(self) -> None:
        archive = self.dir / "mpv-dev.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("libmpv-2.dll", fake_pe(0x8664))
            z.writestr("README.txt", b"licence text")
        code, text = self.run_tool(archive)
        self.assertEqual(code, 0)
        self.assertIn("zip archive", text)
        self.assertIn("libmpv-2.dll", text)
        self.assertIn("Windows DLL, x64", text)
        self.assertIn("README.txt", text)

    def test_7z_is_identified_and_not_claimed_as_listed(self) -> None:
        path = self.write("mpv-dev.7z", b"7z\xbc\xaf\x27\x1c" + b"\x00" * 64)
        code, text = self.run_tool(path)
        self.assertEqual(code, 0)
        self.assertIn("7z archive", text)
        self.assertIn("does not list 7z members", text)

    def test_google_drive_html_page_fails_with_the_download_link_hint(self) -> None:
        path = self.write("libmpv.dll", b"<!DOCTYPE html><html><body>Virus scan warning</body></html>")
        code, text = self.run_tool(path)
        self.assertEqual(code, 1)
        self.assertIn("HTML page, not the file", text)
        self.assertIn("export=download", text)

    def test_unknown_bytes_fail(self) -> None:
        path = self.write("libmpv.dll", b"\x00\x01\x02 not a library")
        code, text = self.run_tool(path)
        self.assertEqual(code, 1)
        self.assertIn("unknown", text)

    def test_hash_match_passes_and_mismatch_fails(self) -> None:
        data = fake_pe(0x8664)
        path = self.write("libmpv-2.dll", data)
        right = hashlib.sha256(data).hexdigest()

        code, text = self.run_tool(path, expect=right.upper())  # case-insensitive
        self.assertEqual(code, 0)
        self.assertIn("SHA-256 matches", text)

        code, text = self.run_tool(path, expect="0" * 64)
        self.assertEqual(code, 1)
        self.assertIn("DOES NOT MATCH", text)

    def test_sha256_is_the_real_digest(self) -> None:
        data = b"the same bytes"
        path = self.write("x.bin", data)
        self.assertEqual(tool.sha256_of(path), hashlib.sha256(data).hexdigest())

    def test_pe_header_parser_rejects_non_pe(self) -> None:
        self.assertIsNone(tool.pe_info(b"MZ" + b"\x00" * 100))  # no PE signature at e_lfanew
        self.assertIsNone(tool.pe_info(b"not a pe file at all"))


def fake_pe_with_imports(dlls: list[str]) -> bytes:
    """A PE32+ file with one section (.idata) that holds an import table naming the given DLLs. Nothing else is real."""
    buf = bytearray(0x400)
    e_lfanew = 0x40
    struct.pack_into("<I", buf, 0x3C, e_lfanew)
    buf[0:2] = b"MZ"
    buf[e_lfanew : e_lfanew + 4] = b"PE\0\0"
    coff = e_lfanew + 4
    struct.pack_into("<HHIIIHH", buf, coff, 0x8664, 1, 0, 0, 0, 240, 0x2002)
    opt = coff + 20
    struct.pack_into("<H", buf, opt, 0x20B)  # PE32+
    dirs = opt + 112
    rva_import, size_import = 0x2000, 20 * (len(dlls) + 1)
    struct.pack_into("<II", buf, dirs + 8, rva_import, size_import)
    sec = opt + 240
    buf[sec : sec + 8] = b".idata\0\0"
    struct.pack_into("<IIII", buf, sec + 8, 0x100, 0x2000, 0x200, 0x200)
    # Descriptors at file offset 0x200 (RVA 0x2000); names from offset 0x300 (RVA 0x2100), one per DLL.
    for i, dll in enumerate(dlls):
        name_rva = 0x2100 + i * 0x20
        struct.pack_into("<IIIII", buf, 0x200 + i * 20, 0, 0, 0, name_rva, 0)
        raw = dll.encode("ascii") + b"\0"
        buf[0x300 + i * 0x20 : 0x300 + i * 0x20 + len(raw)] = raw
    return bytes(buf)


SETUPTOOLS_EXE = Path("/home/claude/.cache/uv/archive-v0/a641I1z5YpLSsHL5N2059/setuptools/cli-64.exe")


class PeImportsTest(unittest.TestCase):
    def test_reads_the_dlls_named_in_the_import_table(self) -> None:
        data = fake_pe_with_imports(["KERNEL32.dll", "VCRUNTIME140.dll"])
        self.assertEqual(tool.pe_imports(data), ["KERNEL32.dll", "VCRUNTIME140.dll"])

    def test_a_file_without_an_import_table_has_no_imports(self) -> None:
        data = fake_pe_with_imports([])
        self.assertEqual(tool.pe_imports(data), [])

    def test_a_truncated_file_is_an_error_not_a_crash(self) -> None:
        data = fake_pe_with_imports(["KERNEL32.dll"])[:0x90]
        with self.assertRaises(ValueError):
            tool.pe_imports(data)

    def test_a_non_pe_file_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            tool.pe_imports(b"not a program")

    @unittest.skipUnless(SETUPTOOLS_EXE.is_file(), "needs a real MSVC binary (setuptools' cli-64.exe)")
    def test_reads_a_real_msvc_binary_import_table(self) -> None:
        # cli-64.exe (setuptools) imports the VC++ runtime, as `objdump -p` shows for the same file. That is why the
        # p2p_core check looks for VCRUNTIME140.
        names = tool.pe_imports(SETUPTOOLS_EXE.read_bytes())
        self.assertIn("VCRUNTIME140.dll", names)
        self.assertIn("KERNEL32.dll", names)
        self.assertEqual(len(names), 10)


if __name__ == "__main__":
    unittest.main()

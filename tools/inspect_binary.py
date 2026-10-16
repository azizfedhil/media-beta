#!/usr/bin/env python3
"""Identify a binary or a download (libmpv, p2p_core) before it goes into an installer.

Reports what the file really is (from its first bytes, not its name), its SHA-256, and, for zip archives and Windows
PE files, what is inside. Run it on the Google Drive download and on any mirror copy:

    python3 tools/inspect_binary.py ~/Downloads/libmpv-2.dll
    python3 tools/inspect_binary.py p2p-core/target/release/p2p_core.dll --imports
    python3 tools/inspect_binary.py ~/Downloads/file --expect-sha256 <hex>   # exit 1 on a mismatch

It reads the file only. It does not extract anything and needs no network.

Exit codes: 0 = identified (and the hash matched, if one was given); 1 = hash mismatch, or the file is not what it
should be (an HTML page, an unknown format); 2 = the path could not be read.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import struct
import sys
import zipfile
from pathlib import Path

PE_MACHINES = {0x8664: "x64", 0x014C: "x86", 0xAA64: "ARM64"}
IMAGE_FILE_DLL = 0x2000


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pe_info(data: bytes) -> dict | None:
    """Reads the COFF header of a Windows executable. Returns None if the bytes are not a PE file."""
    if len(data) < 0x40 or data[:2] != b"MZ":
        return None
    (e_lfanew,) = struct.unpack_from("<I", data, 0x3C)
    if e_lfanew + 24 > len(data) or data[e_lfanew : e_lfanew + 4] != b"PE\0\0":
        return None
    machine, _sections, _stamp, _symtab, _nsyms, _opt_size, characteristics = struct.unpack_from(
        "<HHIIIHH", data, e_lfanew + 4
    )
    return {
        "machine": PE_MACHINES.get(machine, f"unknown (0x{machine:04x})"),
        "is_64bit": machine == 0x8664 or machine == 0xAA64,
        "is_dll": bool(characteristics & IMAGE_FILE_DLL),
    }


def describe_pe(info: dict) -> str:
    kind = "DLL" if info["is_dll"] else "executable"
    return f"Windows {kind}, {info['machine']}"


def pe_imports(data: bytes) -> list[str]:
    """DLL names in a PE file's import table, read in Python so it works on any OS (CI's Windows runner has no objdump).

    Raises ValueError if the bytes are not a PE file, or the file is cut short.
    """
    if pe_info(data) is None:
        raise ValueError("not a PE file")
    try:
        return _read_import_table(data)
    except (struct.error, IndexError) as exc:
        raise ValueError("truncated or damaged PE file") from exc


def _read_import_table(data: bytes) -> list[str]:
    e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
    coff = e_lfanew + 4
    num_sections, = struct.unpack_from("<H", data, coff + 2)
    opt_size, = struct.unpack_from("<H", data, coff + 16)
    opt = coff + 20
    magic, = struct.unpack_from("<H", data, opt)
    dirs = opt + (112 if magic == 0x20B else 96)  # start of the data directories: PE32+ or PE32
    import_rva, _size = struct.unpack_from("<II", data, dirs + 8)  # directory 1 = the import table
    if import_rva == 0:
        return []

    sections = []
    sec = opt + opt_size
    for i in range(num_sections):
        at = sec + i * 40
        vsize, va, raw_size, raw_ptr = struct.unpack_from("<IIII", data, at + 8)
        sections.append((va, max(vsize, raw_size), raw_ptr))

    def offset(rva: int) -> int:
        for va, size, raw in sections:
            if va <= rva < va + size:
                return rva - va + raw
        raise ValueError(f"RVA 0x{rva:x} is outside every section")

    names: list[str] = []
    desc = offset(import_rva)
    while True:
        original_first_thunk, _stamp, _fwd, name_rva, first_thunk = struct.unpack_from("<IIIII", data, desc)
        if original_first_thunk == 0 and name_rva == 0 and first_thunk == 0:
            break
        start = offset(name_rva)
        end = data.index(b"\0", start)
        names.append(data[start:end].decode("ascii", "replace"))
        desc += 20
    return sorted(set(names), key=str.lower)


def classify(head: bytes) -> str:
    if head[:4] == b"PK\x03\x04":
        return "zip"
    if head[:6] == b"7z\xbc\xaf\x27\x1c":
        return "7z"
    if head[:2] == b"MZ":
        return "pe"
    if head[:2] == b"\x1f\x8b":
        return "gzip"
    if head[:6] == b"\xfd7zXZ\x00":
        return "xz"
    if head[:4] == b"Rar!":
        return "rar"
    stripped = head.lstrip().lower()
    if stripped.startswith((b"<!doctype html", b"<html")) or (stripped.startswith(b"<") and b"html" in stripped[:200]):
        return "html"
    return "unknown"


def inspect(path: Path, expect_sha256: str | None, imports: bool) -> tuple[int, list[str]]:
    lines: list[str] = []
    size = path.stat().st_size
    with path.open("rb") as f:
        head = f.read(4096)  # enough for the magic bytes and the PE/COFF header
    kind = classify(head)
    digest = sha256_of(path)

    lines.append(f"file:    {path}")
    lines.append(f"size:    {size:,} bytes")
    lines.append(f"sha256:  {digest}")

    code = 0
    if kind == "html":
        lines.append("type:    HTML page, not the file.")
        lines.append("         A Google Drive /view link returns this. Use the download link:")
        lines.append("         https://drive.usercontent.google.com/download?id=<id>&export=download&confirm=t")
        code = 1
    elif kind == "pe":
        info = pe_info(head)
        if info is None:
            lines.append("type:    starts with MZ but has no valid PE header")
            code = 1
        else:
            lines.append(f"type:    {describe_pe(info)} (by its header; the name says {path.suffix or 'no extension'})")
            if imports:
                try:
                    names = pe_imports(path.read_bytes())
                    lines.append("imports: " + (", ".join(names) if names else "(none)"))
                except ValueError as exc:
                    lines.append(f"imports: unreadable ({exc})")
                    code = 1
    elif kind == "zip":
        lines.append("type:    zip archive (by its first bytes)")
        try:
            with zipfile.ZipFile(path) as archive:
                lines.append("members:")
                for member in archive.infolist():
                    detail = ""
                    if member.filename.lower().endswith((".dll", ".so", ".dylib")):
                        blob = archive.read(member)
                        info = pe_info(blob)
                        detail = f" ({describe_pe(info)})" if info else ""
                    lines.append(f"  {member.filename}  {member.file_size:,} bytes{detail}")
        except zipfile.BadZipFile:
            lines.append("         (the zip structure is damaged)")
            code = 1
    elif kind == "7z":
        lines.append("type:    7z archive (by its first bytes). This script does not list 7z members.")
        lines.append("         Run `7z l <file>` with 7-Zip to see what is inside.")
    elif kind in ("gzip", "xz", "rar"):
        lines.append(f"type:    {kind} archive. Not expected for libmpv; check the source.")
        code = 1
    else:
        lines.append("type:    unknown. First bytes: " + head[:16].hex(" "))
        code = 1

    if expect_sha256 is not None:
        expected = expect_sha256.strip().lower()
        if digest == expected:
            lines.append("check:   SHA-256 matches the expected value.")
        else:
            lines.append(f"check:   SHA-256 DOES NOT MATCH. Expected {expected}.")
            code = 1
    return code, lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("path", type=Path)
    parser.add_argument("--expect-sha256", help="Fail (exit 1) unless the file has this SHA-256.")
    parser.add_argument("--imports", action="store_true", help="List the DLLs a PE file imports.")
    args = parser.parse_args(argv)

    if not args.path.is_file():
        print(f"not a file: {args.path}", file=sys.stderr)
        return 2
    code, lines = inspect(args.path, args.expect_sha256, args.imports)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())

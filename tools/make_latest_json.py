#!/usr/bin/env python3
"""Write latest.json for a desktop release: the newest version, where to download each installer, and its SHA-256.

The app reads this file at startup and every few hours. On Windows it downloads the installer in the background,
checks the hash, and offers "Restart to update". On Linux it only shows the release link (updates come by installing
the new RPM).

    python3 tools/make_latest_json.py --version 0.0.1-Alpha --tag desktop-v0.0.1-Alpha --repo owner/name \\
        --windows dist/PearSetup.exe --linux dist/pear-0.0.1-1.x86_64.rpm --out dist/latest.json

The hash only proves the download is the file this release published. It does not prove who published it. Signing the
installer (docs/desktop/code-signing.md) is what gives that.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = 1


def describe(path: Path, repo: str, tag: str) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return {
        "file": path.name,
        "url": f"https://github.com/{repo}/releases/download/{tag}/{path.name}",
        "sha256": digest.hexdigest(),
        "size": path.stat().st_size,
    }


def build(version: str, tag: str, repo: str, windows: Path | None, linux: Path | None, published: str) -> dict:
    platforms = {}
    if windows is not None:
        platforms["windows-x64"] = describe(windows, repo, tag)
    if linux is not None:
        platforms["linux-x64-rpm"] = describe(linux, repo, tag)
    if not platforms:
        raise SystemExit("nothing to describe: pass --windows and/or --linux")
    return {
        "schema": SCHEMA,
        "version": version,
        "tag": tag,
        "published": published,
        "releaseUrl": f"https://github.com/{repo}/releases/tag/{tag}",
        "platforms": platforms,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--version", required=True, help='the version the app shows, e.g. "0.0.1-Alpha"')
    parser.add_argument("--tag", required=True, help='the git tag, e.g. "desktop-v0.0.1-Alpha"')
    parser.add_argument("--repo", required=True, help='GitHub "owner/name"')
    parser.add_argument("--windows", type=Path, help="the Windows installer (PearSetup.exe)")
    parser.add_argument("--linux", type=Path, help="the Linux RPM")
    parser.add_argument("--published", help="ISO-8601 time (default: now, UTC)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)

    for path in (args.windows, args.linux):
        if path is not None and not path.is_file():
            raise SystemExit(f"missing file: {path}")
    published = args.published or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    manifest = build(args.version, args.tag, args.repo, args.windows, args.linux, published)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.out} ({', '.join(manifest['platforms'])})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

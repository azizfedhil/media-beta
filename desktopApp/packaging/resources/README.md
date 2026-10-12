# Bundled libmpv

Pear plays video through libmpv, and the installer bundles it. Users never install or copy it.

The library is **not stored in the repository**. It's ~100 MB, and git is the wrong place for it.
CI fetches it at build time (see `.github/workflows/desktop-ci.yml`):

| Platform | Source | Placed at |
| --- | --- | --- |
| Windows (64-bit) | mpv-dev LGPL x86_64, release `2026-10-11-d94d095814`, SHA-256 pinned in the workflow | `windows-x64/libmpv-2.dll` |
| Linux (64-bit) | distribution package `libmpv2` | `linux-x64/libmpv.so.2` |

Everything in a platform's folder is copied into the installed app's resources folder, which the player searches first.
`checkLibmpv` (in `desktopApp/build.gradle.kts`) fails any `createDistributable` or `package*` task whose file is missing,
so an installer can't go out without the library.

## Building locally

Copy the DLL into `windows-x64/` (or the .so into `linux-x64/`) before `gradle :desktopApp:createDistributable`.
Running from source (`gradle :desktopApp:run`) searches these folders too. The files are ignored by git (`.gitignore`).

## Licence

The Windows library is an LGPL build. Shipping it inside the installer still means the LGPL terms apply: include the
licence text, and offer the libmpv source for the version you ship. Check the release notes before each release.

# Bundled libmpv

Pear plays video through libmpv, and the app bundles it. Users never install or copy it: the installer carries the library,
and the player finds it in the app's own resources folder.

The library itself isn't in the repository. Put it here once, before packaging:

| Platform | Folder | File |
| --- | --- | --- |
| Windows (64-bit) | `windows-x64/` | `libmpv-2.dll`, from an mpv-dev build (for example shinchiro's). Copy any other DLLs from the same archive into the same folder. |
| Linux (64-bit) | `linux-x64/` | `libmpv.so.2`, or leave it out and depend on the distribution's `libmpv2` package in the package's dependencies |

Everything in a platform's folder is copied into the packaged app's resources folder. The build (`createDistributable`, `package*`)
stops with a message if the file for the OS being packaged is missing, so a release can't go out without it.

Running from source (`gradle :desktopApp:run`) searches these folders too, so a source build plays video without an installed app.

## Licence

mpv and libmpv are GPL by default, and the Windows build from shinchiro is GPL. Shipping it inside the installer means the
distributed app must be available under GPL terms (or the build must be an LGPL-only build, see `docs/desktop/phase0-decisions.md`).
Check this before the first public release. The About screen already lists libmpv and its licence.

# libmpv for source runs and for the Windows installer

The player loads libmpv at run time. It is not in the repository: it is a large binary, and its licence (LGPL or GPL,
see `docs/desktop/packaging.md`) is the builder's choice.

For an installed Windows copy, the installer puts libmpv in `%LOCALAPPDATA%\Pear\native`. For a source run, put it in one
of these places:

| Platform | Folder | File |
| --- | --- | --- |
| Windows (64-bit) | `windows-x64/` | `libmpv-2.dll` (the mpv-dev build; any other DLLs from the same archive go in the same folder) |
| Linux (64-bit) | `linux-x64/` | `libmpv.so.2` (or install `libmpv2` / `mpv-libs` from the distribution; then nothing is needed here) |

The loader looks in this order, and stops at the first file that loads:

1. `PEAR_LIBMPV`: the full path of the file.
2. `PEAR_LIBMPV_DIR`: a folder that holds it.
3. The folder of an installed app (`compose.application.resources.dir`).
4. Windows only: `%LOCALAPPDATA%\Pear\native`.
5. This folder (from the project root or from `desktopApp/`), then `desktopApp/packaging/resources/<platform>`.
6. The system's own search: PATH on Windows; on Linux, `libmpv.so.2` first, then the older names.

When it fails, the player shows the folders it searched and the last error. Two common causes:

- A 32-bit DLL. The JVM is 64-bit, so the DLL has to be 64-bit too. `python tools/inspect_binary.py <file>` shows which it is.
- A missing DLL next to `libmpv-2.dll`. Copy the whole archive's DLLs into the same folder.

To point at a copy anywhere on disk, run from a terminal with `PEAR_LIBMPV=C:\path\to\libmpv-2.dll`.

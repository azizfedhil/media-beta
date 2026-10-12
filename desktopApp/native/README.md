# libmpv for the desktop player

The player loads libmpv at run time. It isn't in the repository: it's a large binary and its licence (LGPL or GPL, see
`docs/desktop/phase0-decisions.md`) is the builder's choice. Put it in one of these places:

| Platform | Folder | File |
| --- | --- | --- |
| Windows (64-bit) | `windows-x64/` | `libmpv-2.dll` (the mpv-dev build of libmpv; any other DLLs in the same archive go in the same folder) |
| Linux (64-bit) | `linux-x64/` | `libmpv.so.2` (or install `libmpv2` from the distribution; then nothing is needed here) |

The loader looks in, in order: `PEAR_LIBMPV` (full path of the file), `PEAR_LIBMPV_DIR` (a folder), the folder of an
installed app, this folder (from the project root or from `desktopApp/`), then the system's own search (PATH on Windows).

When it fails, the player shows the folders it searched and the last error. Two common causes:

- A 32-bit DLL. The JVM is 64-bit, so the DLL has to be 64-bit too.
- A missing DLL next to libmpv-2.dll. Copy the whole archive's DLLs into the same folder.

Run from a terminal with `PEAR_LIBMPV=C:\path\to\libmpv-2.dll` to point at a copy anywhere on disk.

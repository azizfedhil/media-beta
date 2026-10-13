# Files bundled into the desktop app

This folder holds what the packaging copies into the installed app: the Rust P2P engine, `p2p_core`. It is copied into the
app's resources folder, where the app finds it.

libmpv is **not** bundled. How it reaches each platform:

| Platform | Where libmpv comes from |
| --- | --- |
| Windows (64-bit) | The installer fetches it, checks its SHA-256, and puts it at `%LOCALAPPDATA%\Pear\native\libmpv-2.dll`. The app does the same at launch if the file is missing or does not match the pin. It sits outside the app folder, so an update keeps it. |
| Linux (64-bit) | The system's libmpv. The RPM is meant to require `mpv-libs` (Fedora, from RPM Fusion) or `libmpv2` (Debian, Ubuntu). That requirement is not wired yet; see `docs/desktop/packaging.md`. |

## What goes in each folder

- `windows-x64/p2p_core.dll`: built by Gradle (`gradle :shared:buildP2pCore`), and by CI on every run.
- `linux-x64/libp2p_core.so`: the same, for Linux.

Everything in a platform's folder is copied into the installed app. **Do not put libmpv here.** A copy left over from an
earlier build would be bundled, and the installer would stop fetching the pinned one. Delete any `libmpv*` file from this
folder before packaging. The `.gitignore` already ignores binaries here, so none are committed.

## The libmpv link and hash

Nothing about libmpv is committed. The link is `libmpvUrl` in the root `gradle.properties`, and an optional second copy is
`libmpvMirrorUrl`. A Windows build downloads the link, checks that the file is a 64-bit DLL, and records its SHA-256 in
`shared/build/libmpv/sha256.txt`. The app then accepts only a file with that hash, and the installer checks the same hash.

The build prints the hash in its log. The first time, compare it with your own download:

    python tools/inspect_binary.py <the downloaded libmpv-2.dll>

A build with `-PlibmpvSkip=true` (for offline work) records no hash: it downloads nothing, and the app never fetches libmpv.

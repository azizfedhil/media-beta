# P2P engine (desktop)

## Batch 4.5a: the engine on desktop

The engine is `p2p-core/` (Rust, librqbit and UniFFI), the same crate the iOS app links. The desktop app uses it
through the UniFFI Kotlin bindings, so the Rust API is the only definition of the engine on both platforms.

- **Build** (`shared/build.gradle.kts`). `gradle buildP2pCore` compiles the crate as a shared library for this OS
  (`cargo rustc --crate-type cdylib`), and copies it to `desktopApp/packaging/resources/<os>/`, where the installer and a
  source run both find it. `gradle generateP2pBindings` generates the Kotlin bindings from that library, in library mode,
  as the iOS script does for Swift. Both run before the desktop code compiles, so **Rust (cargo) must be on the PATH**
  for every build. The generation step checks that the names the app uses exist in the output, and fails with the reason
  if they don't.
- **Why `cargo rustc` and not `crate-type`.** Adding `cdylib` to `Cargo.toml` would also build a dylib for the iOS
  targets. Passing the crate type to the desktop build only leaves the iOS build unchanged.
- **Loading** (`desktopMain/p2p/P2PLibrary.kt`). The engine loads the first time it is asked for. The UniFFI bindings are
  pointed at the file through `uniffi.component.p2p_core.libraryOverride`, set before the first call. The library is
  looked for in `P2P_LIBRARY` (a full path), then the app's resources, then the packaging folder.
- **Wrapper** (`commonMain/p2p/P2PEngine.kt`). `open` (starts the engine if needed and resolves the torrent), `stopStream`,
  `status`, `stop` (the killswitch: closes the sockets, joins the threads, deletes the cached data), and `requestStop` for
  a screen that is going away. One stream at a time. `stop` doesn't wait behind an `open` in progress (a metadata fetch):
  it cancels it, as the engine does. Every engine call runs on `Dispatchers.IO`.
- **Errors** (`P2PFailure`). The engine's `MetadataTimeout` becomes "couldn't find peers", `NoFile` becomes "no playable
  video", `Stopped` becomes a cancellation. The stream URL carries a token for the session, so it is never logged or shown.
- **Test screen**: Settings -> Developer -> P2P. Start the engine on a public test torrent (Big Buck Bunny), print the
  status, stop, and count leftover `p2p-*` folders (0 after a stop).

### Where this differs from the iOS app
- The iOS app starts and stops the engine from the player, with a settings switch, a Wi-Fi gate and idle timers. None of
  that is in 4.5a: it comes with 4.5b to 4.5d.
- The app uses `P2PEngine` directly; a player integration is 4.5c.

### Check it
1. `gradle :shared:build` on Windows and Linux (Rust installed): the library and the bindings are generated, the build
   passes, and `desktopApp/packaging/resources/<os>/` holds the library.
2. Settings -> Developer -> P2P -> Start engine: the log shows the file name and its size. It does not print a URL.
3. Status: the engine is loaded, the stream is open, and the peer count grows.
4. Stop: "Leftover cache folders: 0". Open the cache folder (`AppDirs.cache/P2P`): no `p2p-*` folder remains.
5. Leave the screen while a stream is open: the engine stops (no process sockets left).

### Not compiled
The authoring environment has no Rust or Gradle here. Two names come from UniFFI's output and are checked at build time:
the `libraryOverride` property, and the error variant classes. If the build reports them missing, the message names the
file to change.

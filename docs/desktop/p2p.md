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

## Batch 4.5b: the switch, the gate, and the source lists

- **The switch** (`commonMain/p2p/P2PService.kt`, held by `AppData.p2p`). Off by default. `setEnabled(false)` makes every
  torrent source unplayable at once. It does not stop the engine: a stream already playing carries on until the user
  leaves it (see 4.5c). The settings are saved
  under `p2p.enabled` and `p2p.wifiOnly`, and they are not in a settings backup, so a restored backup can't turn P2P on.
- **Live state**. `canStream` is on and the network is allowed. The player, the source lists and the episode matcher all
  read it, so a change in Settings reaches an open source list at once.
- **The gate** (`P2PService.gate`). The add-on client's `streamFilter`. It keeps every stream, torrents included, so the
  list can show a torrent as unavailable and say why. With P2P on, it waits for the network check first, as iOS does.
- **The network check** (`P2PNetworkWatch`). A phone can tell Wi-Fi from cellular and hotspots; this desktop can't, so its
  check always allows. The Wi-Fi only setting is saved, and Settings says it doesn't limit P2P on this computer.
- **The lists**. A torrent is dimmed and can't be clicked while it isn't playable. Under each group, a line says how many
  torrents are unavailable and why ("P2P is off. Turn it on in Settings → Peer-to-peer").
- **The player** hides torrents while they aren't playable. Clicking a torrent while P2P is on reports that playback
  isn't connected yet: that is 4.5c.

### Where this differs from the plan
- The plan's done-when said torrents "appear only with P2P enabled". The iOS app lists them, dimmed, with the reason,
  which is the spec this batch follows. If you want them hidden instead, it's a one-line filter in the two source lists.

### Check it
1. Settings → Peer-to-peer is off. Open a title: its torrent sources are dimmed, can't be clicked, and the group explains
   why.
2. Turn it on (the confirmation appears). The torrents are no longer dimmed. A click reports that playback isn't
   connected yet.
3. Turn it off while the source list is open: the torrents dim at once. A stream already playing carries on until the user
   leaves it; the switch never stops the engine by itself.
4. Restart the app: the switch keeps its state. A backup restored onto a fresh install leaves P2P off.

### Not compiled
As before, this environment has no Gradle or Kotlin compiler. Run `gradle :shared:build` first (the P2P service has a
common test: `P2PServiceTest`), then `gradle :desktopApp:run`.

## Batch 4.5c: resolve and play

- **Opening a torrent** (`shell/PlayerScreen.kt`). A torrent request carries its `P2PSource` (hash, trackers, and the file
  Torrentio picked; `StreamItem.p2pSource()`). The player calls `engine.open` first. The loader shows while it opens, with
  the status line under it (`p2pStatusLine`: peers, speed, progress), read once a second. When the engine returns the
  loopback URL, mpv loads it. Seeking works because the engine answers HTTP range requests.
- **Errors**. Switched off, or on a network that isn't allowed, the player says so before it tries. The engine's errors
  appear in the same error card as a failed link: no peers, no video in the torrent, or any other failure.
- **The engine's life** (the rule for this batch). The engine stops only when the user leaves the stream: the player's close,
  back and window close all call `requestStop`. A link played after a torrent ends that stream (`stopStream`) and leaves
  the engine idle. Switching torrent to torrent replaces the stream and keeps the engine. The P2P check screen also ends
  its stream when left, since its stream is one the user started.
- **Not yet** (4.5d). Idle timeouts, pausing into the background, network changes, power saving, and the cleanup of
  temporary data after a crash.

### Check it
1. Turn P2P on. Play a torrent from a title: the loader shows the status line (finding peers, then peers and speed), then
   the film plays. Seek forward and back.
2. Pause, then leave the player: the engine stops (the P2P check screen reports `Engine loaded: no` and no `p2p-*` folder
   remains in the cache).
3. Play a torrent, turn P2P off in Settings (the player can't be reached from Settings, so close the player first and then
   turn it off): nothing stops a stream still playing. Only leaving it does.
4. Play a torrent that has no peers: the error card says "Couldn't find peers for this torrent. Try another source."
5. Play a season pack: the file of the episode you picked plays, not the largest file.

### Not compiled
As before, this environment has no Gradle or Kotlin compiler. Run `gradle :shared:build` first (`P2PStreamTest` and
`P2PServiceTest` are common tests), then `gradle :desktopApp:run`.

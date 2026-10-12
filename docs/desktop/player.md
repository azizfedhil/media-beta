# Player (Batches 4.1 to 4.3b)

- `player/PlayerEngine.kt`: the interface (state flows, load, seek, tracks, `setAspectMode`, `setHdrToneMapping`).
- `desktopMain/.../mpv/MpvPlayerEngine.kt`: libmpv over JNA. Created with `vo=libmpv`, so there is no mpv window; frames come through the render API.
- `desktopMain/.../mpv/MpvVideoRenderer.kt`: mpv software renderer (`api_type=sw`, format `bgr0`). mpv writes straight into a Skia `Bitmap`'s pixels (rows padded to 16 px); Skia makes one immutable `Image` per frame. Runs on its own thread, publishes the latest frame as a `StateFlow`. The render context is freed before the mpv handle.
- `VideoSurface` (expect/actual): a Canvas that draws the latest frame. Performance mode caps the render size at 1080p (the picture is scaled up when drawn); otherwise the cap is 4K.
- Aspect: Fit (keepaspect), Fill (`panscan=1`), Stretch (`keepaspect=no`).
- HDR: software rendering has no GPU tone mapper, so PQ/HLG video gets an ffmpeg `zscale` + `tonemap=hable` filter (`@pearhdr`) while it plays. Needs an ffmpeg with libzimg (the shinchiro Windows build has it).
- Hardware decoding uses the copy variant (`auto-copy-safe`) because the software path can't take GPU surfaces.
- Fullscreen: `WindowControl.isFullscreen` (Main.kt sets `WindowPlacement`). The shell hides the sidebar / tab bar while a player is on top.

## Overlay (Batch 4.3a)

Code: `shared/.../player/ui/` (overlay, pieces, pure rules) and `shell/PlayerScreen.kt` (hosts it). Spec: `PlayerScreen` in `PlayerView.swift`.

- `PlayerOverlay(engine, title, subtitle, look, glass, errorMessage, onClose, onToggleFullscreen, actions, video)`: one overlay for every platform. The video goes in the `video` slot, which is the Haze source, so blur works over live frames.
- Layout as on iOS: round close button top left (no title bar: the title sits above the seek bar), skip -10 / play-pause / skip +10 in the centre, title + episode line left and the option pill right above a glass seek bar. Narrow windows (< 620 dp) drop the pill under the title.
- Seek bar: elapsed, track with the buffered range (lighter, from the playhead to `demuxer-cache-time`), time left. Hover shows a time preview (no thumbnails). Dragging commits on release; the bar holds the target for up to 1.5 s so it doesn't jump back while mpv seeks. The bar reads the engine clock itself, rounded to 0.2 s, so nothing else recomposes while playing.
- Volume: superseded in 4.3c. The speaker now turns the pill into a slider (see "Controls pill" below). Still mpv's own volume (0..1).
- Auto-hide: 3.5 s after the last mouse movement, only while playing or buffering. Controls stay while paused, loading, ended, failed, dragging the seek bar, hovering the controls or using the volume slider. The cursor is hidden together with the controls (`hiddenPointerIcon`, desktop: blank AWT cursor).
- Mouse: click on the picture toggles play/pause (or closes an open panel, 4.3c), double-click toggles fullscreen. (iOS toggles the controls on tap; on desktop hover already does that.)
- The player is always dark (`PearDark`), whatever the app appearance.

### Glass setting (`PlayerLook`)

| `player.glass` | video | look |
| --- | --- | --- |
| off | any | **Flat**: the iOS flat fills (circle black 0.4 / accent 0.75, capsule 0.55, card 0.88) |
| on | paused, loading, error | **Blur**: the app's glass style (Haze) |
| on | playing | **Tint**: black tint + hairline edge, no blur, unless `desktop.playerBlurWhilePlaying` is on, then **Blur** |

The app-wide glass mode set to Flat also means Tint in the player. Settings -> Playback has the "Liquid Glass controls" switch and, under it, "Blur controls while playing" (desktop only, default off until the numbers are measured; see `phase0-decisions.md`).

### Not in 4.3a (by plan)

Not planned: the "pear." loader animation and the paused-state show logo; the overlay shows a spinner instead. (The pill, speed, sources and info card are 4.3c, below.)

## Resume, progress and history (Batch 4.3b)

Code: `player/PlaybackProgress.kt` (rules + session, no Compose, unit tested), `shell/PlayerScreen.kt` (wiring), `shell/AppExit.kt` (window close).
Spec: `resumePoint`, `save`, `finalizeCurrent`, `logPlayTime` and the 15 s tick in `PlayerScreen` (PlayerView.swift).

- **Resume.** `ProgressRules.resumePoint(entry, key)`: the saved position when it is the same episode (or the movie), past 30 s and not finished (< 92%). The engine opens straight at it (`start=` in mpv, no load-then-seek), and the seek bar shows it while the source opens (`openedAt`). There is **no "resume or start over" prompt**: the iOS app has none, so neither does this. Start over by dragging the seek bar to 0.
- **When progress is saved.** On pause and on reaching the end, every 15 s while playing, on the close button, on back, when the screen is disposed, and on the window's close button. Periodic and pause saves are `checkpoint` (storage only, so Home doesn't redraw behind the player); the save on leaving is `update` (the lists on screen refresh, Continue Watching moves up). Nothing is saved before the length is known or before anything has played.
- **Start guard (not in iOS).** When a title opens at a resume point, saves wait until the playhead has been seen within 10 s of it. Closing while the file is still opening can't overwrite the saved point with "0:00".
- **Watched.** `HistoryEntry.isFinished` (92%) decides it, as before: a show gets its "watched through S·E" marker, a movie its done flag. A saved movie watched through moves to Watched in the local library (`markWatchedIfSaved`); titles you never saved are not added.
- **Watch log.** The statistics page's watch time: 15 s ticks while the state is Playing (not paused, not buffering), each capped at 20 s, flushed on leaving.
- **Simkl.** `start` at 0% on the first Playing state of a request, `stop` with the percent watched on leaving (once per request, only when the length is known). Only when the profile's library is Simkl and it is connected (`LibraryPrefs.usesSimkl`). Simkl marks an episode watched itself at 80%. A request without an IMDb id is not scrobbled. The `stop` is sent from a background task: on window close the process may exit before it goes out.
- **Window close.** The title bar button exits the process without disposing the UI, so `Main.kt` calls `AppExit.runAll()` first; the player registers its save there while it is on screen.
- **Source memory.** `PlayRequest` already carries `sourceAddonID` and `sourceSignature` (set by the source sheet); the save path never drops them, and `PlayRequest.withSource(...)` swaps a stream while keeping the title, key and artwork (used by the source switcher in 4.3c). The next-episode lookup that prefers this source is 4.3d.
- **Continue Watching artwork.** The Detail screen now puts the episode still (or the movie backdrop) in `PlayRequest.thumb` and the title logo in `logo`, as DetailView.swift does; without them the card had no image.

### Differences from the iOS player
1. The start guard above.
2. Time since the last 15 s tick is dropped when a pause lands (iOS does the same); no "app went to background" save, since a desktop window doesn't get suspended.
3. No Simkl `pause` scrobble (iOS doesn't send one either).

### Check it
1. Play a movie 2 minutes in, press the X. Home -> Continue Watching shows it with a bar; open it again: it starts at the same minute, and the seek bar shows that time during loading.
2. Play past 5 minutes, then close the **window** (title bar button), not the X. Start the app: the movie is in Continue Watching at about that point.
3. Pause, wait, kill the app from Task Manager / `kill -9`: at most the time since the pause is lost (the pause saved).
4. Play 20 s of a title, X, reopen: it starts at 0:00 (under the 30 s threshold), and it isn't in Continue Watching.
5. Let an episode play to the end: it leaves Continue Watching, the show gets "watched through" that episode (the profile statistics and Up Next agree).
6. Open an episode: Continue Watching shows the episode still, not a blank card.
7. Simkl connected and set as the library: play 2 minutes and press X; within a minute the title shows in Simkl's "watching" or recent history. Library set to Local: nothing is sent.
8. The profile statistics page: watch time grows by about what you watched.

### Not compiled
The authoring environment has no Gradle. `gradle :shared:desktopTest --tests "*PlaybackProgress*" --tests "*ProgressRules*" --tests "*AppExit*"` runs the new tests; then `gradle :desktopApp:run` for the list above.

## Controls pill, panels and info card (Batch 4.3c)

Code: `player/ui/PillItems.kt` (pill rules), `player/ui/OptionPill.kt` (the pill), `player/ui/PlayerPanels.kt` (speed, sources, info panel), `player/ui/InfoCard.kt` and `player/ui/PlayerInfo.kt` (the card and its data), `player/ui/SourcePanelRules.kt`, `player/ui/PlayerLogic.kt` (`PlayerPanel`, `SpeedMath`), `settings/PlayerControlsSettingsScreen.kt`, `shell/PlayerScreen.kt` (hosts it all), `shell/PrefState.kt` (`rememberPref`, a setting that follows changes). Spec: `PlayerControlsSettings.swift`, `PlayerView.swift` (`iconRow`, `pillLayout`, `speedPanel`, `sourcesPanel`, `infoPanel`, `loadInfo`), `PlayerInfoCard.swift`, `PlayerVolume.swift`, `SourceFilter.swift`.

### The pill
- `PillItem` has the iOS raw names and order. `PillItem.decodePinned` / `decodeOrder` / `encode*` follow the Swift code exactly, so a backup from iPhone restores the same layout. Prefs: `player.pillPinned`, `player.pillOrder`.
- Folding: above four buttons the pill shows the pinned ones and a chevron. The chevron unfolds the rest to the left and the pill folds itself again 5 s after the last tap. Folded width is fixed, so the title never moves; the unfolded pill grows over it. Widths come from slot counts (`pillWindowSlots`), not measurements.
- Speaker: turns the pill into a volume slider (mute button and slider, at least 260 dp wide). It goes back to the icons 3 s after the last change, and the countdown pauses while the slider is dragged.
- Panels close the pill's folds (`panelOpen`); the pill keeps the controls up while it is open (`onActive`).
- Live: the pill reads both prefs through `rememberPref`, so a change in Settings shows in an open player.
- Buttons in this build: **Speed, Sources, Volume** (`desktopPillItems`), and **Next** while there is an episode after this one (`pillItemsAvailable`, 4.3d). Episodes (the episode carousel) is not built yet; Subtitles and Audio come in 4.4a; Picture in Picture never applies on desktop. Pinning changes nothing visible until there are more than four buttons, and reordering does.

### Desktop-only buttons
Aspect (cycles Fit / Fill / Stretch), 4K performance mode and full screen sit in their own capsule after the pill (`DesktopControls` in `PlayerScreen`). They are not in the pill or in Settings, because the iOS player has none of them. This departs from the plan's "moved into the pill", which conflicted with the Swift pill.

### Panels
- **Speed**: 360 dp card above the pill, slider 0.5x to 2x, label, reset to 1x. `SpeedMath` rounds to 0.01 and snaps to 1x within 0.025. The controls stay up.
- **Sources**: right edge, 360 dp. Playable streams only (`isPlayable(p2pReady = false)`), grouped under each add-on in the user's add-on order, with a menu that narrows to one add-on (a stale choice shows everything). The stream being played has a check mark; picking it closes the panel. Groups appear as each add-on answers. Torrent-only streams are hidden, not shown as "P2P is off" (4.5).
- **Info card**: episode or movie. Opens from the title's chevron, and covers the controls (as on iOS). The episode card uses TMDB's episode (still, synopsis, air date, runtime, TMDB vote). If the episode has no synopsis yet, the series description is shown with "About the series". The movie card uses TMDB details and MDBList ratings, with the TMDB vote and the catalog score filling in. The card is 230 dp wide with the still, and it stacks below 520 dp.
- **Switching source** (`switchSource` in `PlayerScreen`): banks the progress of the stream being left, keeps the playhead as the load position, and swaps the request with `withSource`. It does not call `PlaybackProgress.begin()`, so the Simkl start flag and the resume guard stay as they are.
- A click on the picture closes an open panel instead of toggling playback (`tapBackground` on iOS).

### Settings
Settings -> Playback -> Player controls: a live preview of the folded pill; "Always visible · N of 4" with the lock rules from Swift (a full set cannot take another; the last one cannot be removed); the order list, reordered by dragging its handle (each step is about one row, and the order is saved when the drag ends); and "Reset to default". Toggles use the codebase's ON/OFF chips (`DevChip`), as the other settings screens do, not iOS switches.

### Check it
1. Play anything. The pill shows Speed, Sources and Volume on the right, with the desktop capsule after them. Tap the speaker: the pill becomes a slider and returns after 3 s.
2. Speed: pick 1.5x, the label and the picture change. Reset returns to 1x.
3. Sources (a title with two add-ons): the list is grouped, the add-on menu narrows it, the stream playing has a check. Pick another stream: playback continues at the same position.
4. Click the title (or its chevron): the info card opens and covers the controls. A click on the picture closes it.
5. Settings -> Player controls: reorder the rows by dragging the handle, then open a player: the pill follows. Pin and unpin: the counter and the lock rules behave; the player changes only once it has more than four buttons (4.3d).
6. Narrow the window below 620 dp: the pill drops under the title.

### Not compiled
As before, the authoring environment has no Gradle or Kotlin compiler. Run `gradle :shared:desktopTest --tests "*PillItems*" --tests "*PlayerPanelRules*" --tests "*PlayerInfo*"` first, then `gradle :desktopApp:run` for the list above.

## Finding libmpv (follow-up)

The loader (`MpvNativeLoader` in `LibMpv.kt`) looks in this order: `PEAR_LIBMPV` (full path of the file), `PEAR_LIBMPV_DIR` (a folder),
the folder of an installed app, `desktopApp/native/<platform>/` (from the project root or from `desktopApp/`), then the system's own search
(PATH on Windows). The player's error card lists the folders it searched and the last error from the first failed load, so a 32-bit DLL or a
missing dependency is visible. The library is not in the repository; see `desktopApp/native/README.md`.

## Bundled libmpv (replaces the "copy it yourself" steps)

- The Windows and Linux packages carry libmpv. Its files sit in `desktopApp/packaging/resources/<platform>/` and `appResourcesRootDir`
  copies them into the app's resources folder, which the loader checks first.
- `checkLibmpv` (in `desktopApp/build.gradle.kts`) runs before any `createDistributable` or `package*` task. It fails with the path of the
  missing file, so a release can't go out without the library.
- Source runs find the same folder (`desktopApp/packaging/resources/`), so `gradle :desktopApp:run` plays video without an installed app.
- Users never copy a file. A missing library means an incomplete install: the message says to reinstall.
- Build check: after `createDistributable`, `app/resources/` in the output should contain `libmpv-2.dll`. If it doesn't, the Compose
  version doesn't copy platform folders the way this assumes; the fix is in `appResourcesRootDir`.

## Player: glass, one pill, Display panel, centred info card, larger controls

- **Glass while playing** is on by default (`desktop.playerBlurWhilePlaying`, default now `true`). Before, the controls were a flat
  dark tint whenever the video played, so there was no glass. The Settings switch turns it off if a machine can't keep up.
- **One pill.** The aspect, 4K performance and full-screen buttons are no longer a second capsule. A Display button is the pill's
  last slot. It opens the Display panel: picture (Fit / Fill / Stretch), fast 4K mode, and full screen. Double-click still
  toggles full screen.
- **Info card** (synopsis) is centred on the screen.
- **Larger controls.** `PLAYER_UI_SCALE` (1.25) scales the controls and panels, text included, through the local density. The
  video behind them is not scaled.

## Skip intro, next episode, autoplay (Batch 4.3d)

Code: `player/ui/SkipRules.kt` (the rules, pure), `player/ui/SkipOverlay.kt` (the buttons), `player/EpisodeSupport.kt` (next-episode lookup, `SourceChooser`), `shell/PlayerScreen.kt` (wiring, switching). Spec: `SkipOverlay`, `loadAux`, `switchEpisode`, `playNext` and the `didEnd` autoplay in PlayerView.swift; `UpNext.swift`, `EpisodeSupport.swift`, `IntroClient.swift`. The data side (`UpNext`, `EpisodeLoader`, `IntroClient`) already existed from 2.2 and 2.4.

- **One skip button, bottom right** above the bar (140 dp up while the controls show, 40 dp when they hide). It changes with the moment, as `choose` in `SkipRules` decides:
  1. **Next Episode** in the credits, or in the last 45 s once past the first minute, when there is an episode after this one.
  2. **Skip Intro / Recap / Credits / Preview** for the segment the playhead is in (half a second early, a second and a half before its end). A segment with no end runs to the end of the file, so a credits skip with nothing after it jumps to the end.
  3. **Skip Ns**, the manual button, only when the setting (Settings -> Playback -> Manual skip button) is above 0, the show has no timestamps, the episode did not open part way in, the playhead is between 3 s and 420 s, and the controls are showing. It goes away after 12 s, and after one use per episode. Each time it is offered again the 12 s start over.
- **Hidden under panels and errors**, as on iOS. The speed panel hides it too.
- **Timestamps** come from `IntroClient` (TheIntroDB, TMDB id first, then IMDb) and are looked up again for every episode. The playhead is read in half-second steps, so the overlay doesn't redraw for every frame.
- **Next episode** (`EpisodeSupport.next`): `UpNext.next`, which is the same rule as the Home screen's Up Next card (the next episode of the season, else episode 1 of the next season, only when it has aired; when no metadata answers it assumes the following episode exists). A movie has none.
- **Choosing its stream** (`SourceChooser`, the same order as `SourceResolver.request`): the add-on and name of the source being watched now (so the quality and provider carry on), then the show's pinned source, then the first playable stream of any add-on in the user's order. Only direct links count while P2P is off. A name that is gone falls back to that add-on's first playable stream.
- **Switching** (`switchEpisode`): the stream is found first, so a failure leaves the current episode playing and shows "No playable source found for that episode" in a toast at the top. On success the episode being left is banked (`progress.finish`: save, watch time, Simkl stop), then the new one starts at its own resume point, with `progress.begin` clearing the per-episode state (Simkl start, the start guard). Panels close.
- **Next** is the pill's last button while there is an episode after this one, and it is greyed out while the next stream is being found. It is the same action as the skip button's Next Episode.
- **Autoplay** (`player.autoplayNext`, on by default, Settings -> Playback): when the file ends and there is a next episode, it plays. With autoplay off the file just ends, and Next is still there.
- **End of the show**: no next episode, so no Next button, no autoplay, and the ended state keeps the controls up.

### Differences from the plan (4.3d)
1. The plan said "Up Next card near the end". The iOS player has no card: the skip button shows Next Episode in the last minute. That is what is built. The Up Next **card** is the Home screen's Continue Watching card, which already exists.
2. The plan said the next episode "prefers the pinned one". The iOS code (the source of truth) prefers the source being watched now, then the pin. This build follows the code.
3. The Episodes carousel (the pill's episode button and its season / episode panel) is **not** in this batch. It's its own batch, so Episodes stays out of the pill for now.
4. The seek bar's "opening" hold (`openedAt`) now follows the load position rather than the resume point, so a source switch and an episode switch hold the right time while the new file opens. For a fresh episode it is zero, as before.

### Check it
1. An episode with timestamps (a show TheIntroDB knows): at the start of the intro the button reads **Skip Intro**, a click lands at the intro's end, and the button goes away.
2. In the last minute of an episode with a next one: **Next Episode** shows; a click plays the next episode, the title line shows the new S·E, and the saved progress of the old one is kept (Continue Watching).
3. Let an episode run out with autoplay on: the next one starts by itself. Turn autoplay off in Settings: the episode stops at the end, and Next in the pill still plays it.
4. An episode with no timestamps and the manual skip set to 30 s (Settings): Skip 30s shows in the first minutes, once; it goes away after about 12 s, and it doesn't come back for that episode.
5. Resume an episode mid-way (opens part way in): no manual skip button.
6. Next episode of a show whose next episode has no stream: a toast says so, and the current episode keeps playing.
7. Last episode of a show: no Next button, no autoplay; the ended state keeps the controls up.
8. Credits of the last episode: **Skip Credits** jumps to the end, and the player ends.

### Not compiled
As before, the authoring environment has no Gradle or Kotlin compiler. Run `gradle :shared:desktopTest --tests "*SkipRules*" --tests "*NextPill*" --tests "*UpNext*" --tests "*IntroClient*"` first, then `gradle :desktopApp:run` for the list above.

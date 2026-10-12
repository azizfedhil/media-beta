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

## Outlines (brand pass)

The outlines of the player's surfaces are half as strong as before: the blur border and the tint edge (`PLAYER_OUTLINE` in `player/ui/PlayerSurfaces.kt`, passed to `GlassSurface(outlineStrength = ...)`) and the info card's still. Frosted and Flat use the theme's outline colour at half its alpha. Glass and Clear draw their own edge through Haze, so that edge is unchanged.

## Pill, full screen and folding (brand pass 2)

- **Display is a pill button.** Aspect and fast 4K mode open from it (the Display panel). It counts towards the pill's four buttons, so it can fold, and `PillItem.Display` is the desktop-only addition to the enum (its raw name `display` is new; iOS ignores names it doesn't know).
- **Full screen is always the pill's last slot.** It is not one of the layout's items, so it never folds and never counts towards the four. It shows the enter or exit icon. It hides only while the volume slider has the pill. The Display panel no longer has a full screen row.
- **Folding.** The chevron appears when more than four buttons exist (`FOLD_ABOVE`). On desktop that means an episode: Speed, Sources, Volume, Next and Display are five, and Sources, Next and Display fold away behind the chevron by default. A movie has four buttons (Speed, Sources, Volume, Display), so nothing folds there. Subtitles, Audio and Episodes will add more, and folding will then be common on films too.

## Continue Watching text (wide)

The card text was never scaled in the wide layout: the hover lift shadowed the card scale, so the text got 1.0 or 1.02. It now follows the layout: `WideScale.CONTINUE_TEXT` (1.20) in wide, 1.0 in phone, for the title, the S·E line and the episode name.

## Defaults

- The app's glass is **Glass** (Haze glass regular). A saved choice in Settings still wins, so an install that already chose Frosted keeps it until it's changed.
- Episodes show in **one row**. The Two rows option is still in the Seasons header on wide windows.
- Episode stills are **w780** (was w300). Saved Continue Watching links carrying the small sizes are upgraded when shown.

## Subtitles and audio: tracks, language and delay (Batch 4.4a)

Code: `player/TrackRules.kt` (the picks, the labels, the delay steps; pure, unit tested), `player/ui/TrackPanels.kt` (the menus), `player/ui/OptionPill.kt` (the buttons), `shell/PlayerScreen.kt` (picking on load, the delays), `settings/SubtitleSettingsScreen.kt` (the defaults), `desktopMain/.../mpv/MpvPlayerEngine.kt` (`sid`, `aid`, `sub-delay`, `audio-delay`). Spec: `subtitlePanel`, the audio `Menu`, `bestSubtitle` and `refreshTracks` in PlayerView.swift and SubtitleSupport.swift.

- **Pill.** Subtitles joins the pill when the file has subtitle tracks, and Audio when it has more than one audio track (as on iOS). The subtitles button shows as on while a track plays.
- **Subtitles panel** (right edge, covers the controls like Sources): Off, then each track with its flags (Forced, SDH, Default), the track playing marked. Picking a track keeps the panel open. Below it, the subtitle delay.
- **Audio panel** (floats above the pill, like Speed): the audio tracks, then the audio delay.
- **Delay.** Steps of 0.1 s, up to 10 s either way, with a Reset that appears only while the delay is set. mpv `sub-delay` and `audio-delay` move the subtitles and the sound, so the picture stays where it was. The delays last for the window: they are set again each time a file starts playing, and they are not saved.
- **Auto-pick on load**, once per file, when its tracks arrive:
  - Subtitles: the preferred language picks a normal track, then one that isn't forced, then any in that language. A language with nothing in the file leaves the file's default. The preference is Settings → Subtitles → Default language, or the last language picked in a menu.
  - Audio: the preferred language picks the track the file marks as default if it is in that language, else the first in it. The preference is Settings → Subtitles → Default audio language (desktop only, Off by default, so nothing changes until it is set).
  - A track picked in a menu sets the language for the next episodes as well, as the subtitle choice does on iOS.
- **The track list is cleared when a new file starts.** The list belongs to the file being left until the new one reports its own; without the clear, the language pick could select a track of the previous file.

### Where this differs from iOS
1. iOS has no delay controls and no audio language preference. Both are desktop additions, as the plan asked for them.
2. iOS leaves a subtitle the file already selected alone. This picks the viewer's language even when the file marks another subtitle as default.
3. iOS reads SDH from the engine. mpv's track list gives no such flag here, so SDH is read from the track title ("SDH", "HI", "hearing").

### Not in 4.4a
Online subtitles (OpenSubtitles and add-on sources, 4.4b). Subtitle appearance and the libass question (4.4c). Episodes (the episode carousel) is still not built.

### Check it
1. A file with English and French audio, and the default language set to French: it starts in French, and the audio button shows the French track as playing.
2. A file with a forced English subtitle and a full English one: with English as the default language, the full one plays.
3. Set the subtitle delay to +1.5 s: the subtitles arrive 1.5 s later than the picture. Reset: they line up again. Repeat with the audio delay.
4. Change to the next episode: the delays stay where you set them, and the language you picked in a menu is used again.
5. Open a file with one audio track: the audio button is not on the pill.
6. A file with no subtitles: the subtitles button is not on the pill.
7. The track names read as "English · Forced", "English (Commentary)" and so on, never as raw mpv titles.

### Not compiled
As before, run `gradle :shared:desktopTest --tests "*TrackRules*" --tests "*TrackPill*" --tests "*PillItems*" --tests "*DesktopPill*"` first, then `gradle :desktopApp:run`.

## Online subtitles (Batch 4.4b)

Code: `addons/SubtitleSources.kt` (OpenSubtitles plus the add-ons' subtitle sources, merged and cached), `addons/OpenSubtitlesClient.kt` (unchanged, the same Stremio v3 endpoint as iOS), `player/TrackRules.kt` (`TrackPicker.autoOnline`), `player/ui/TrackPanels.kt` (the ONLINE section), `shell/PlayerScreen.kt` (lookup, adding, auto-add), `PlayerEngine.addExternalSubtitle` and its mpv `sub-add`. Spec: `OpenSubtitlesClient`, `loadOnlineSubtitles`, `useOnline` and the OPENSUBTITLES section in PlayerView.swift.

- **Where the files come from.** For each episode or movie, OpenSubtitles (`/subtitles/{movie|series}/{imdb}[:{season}:{episode}].json`) and each enabled add-on that declares a `subtitles` resource (`{base}/subtitles/{type}/{id}.json`, the same shape). OpenSubtitles is listed first, then the add-ons in the user's add-on order. A file with the same link is listed once. The lookup runs once per episode, and the answers are kept for the run, empty ones too.
- **One list.** The subtitle menu shows the file's own tracks, then an ONLINE section with the files not added yet (spinner while the lookup runs). Each file reads as its language and source ("English · OpenSubtitles"), with a number when a language has more than one ("English 2 · OpenSubtitles"). When a file is added, it moves up into the track list, labelled with its source ("English (OpenSubtitles)"), and is selected.
- **Adding.** `addExternalSubtitle` runs mpv `sub-add <url> select <source> <language>`. The menu waits up to 10 s for the new track to appear, then selects it. If it doesn't appear, the player says "Couldn't load that subtitle". Picking the same file again only selects it.
- **Auto-add, once per file** (`loadOnlineSubtitles` with auto): when the file is playing or has its tracks, and a language is preferred, the file has no track in that language, and no subtitle is on, the first online file in that language is added and selected.
- **The subtitle button** shows whenever online lookup is on, even for a file with no tracks of its own (iOS does the same).
- **The switch** is Settings → Subtitles → Online subtitle lookup. It turns off both OpenSubtitles and the add-on sources.

### Where this differs from iOS
1. iOS asks only OpenSubtitles. The add-on sources are a desktop addition, as the plan asked for them.
2. The added file's source name is its title in the track list, so the title and the menu agree.

### Not in 4.4b
Subtitle appearance and the libass question (4.4c). The episode carousel is still not built.

### Check it
1. A film with no embedded subtitles, and English as the default language: the subtitles button is on the pill, the menu shows an ONLINE section, and English starts playing by itself. Its track reads "English (OpenSubtitles)".
2. Open the subtitles menu on a film with several online languages: the preferred language is listed first, each language once, and the spinner shows until the lookup has finished.
3. Pick a French file: it plays at once, the menu marks it, and it is gone from the ONLINE section (it is in the track list now). Switch to another episode and back, then pick it again: the list is the one already fetched for that episode, and the file is added again to the new file.
4. An add-on with a `subtitles` resource: its files appear with its name as the source.
5. Turn the online lookup off in Settings: the subtitles button stays only while the file has subtitle tracks, and there is no ONLINE section.
6. A file that doesn't load (a broken link): the toast says "Couldn't load that subtitle", and the current subtitle stays on.

### Not compiled
As before, run `gradle :shared:desktopTest --tests "*SubtitleSources*" --tests "*OnlinePick*" --tests "*TrackRules*"` first, then `gradle :desktopApp:run`.

## Subtitle drawing and style (Batch 4.4c)

- **Who draws what** (`player/SubtitleRendering.kt`). Plain text (SRT, WebVTT, MP4 text, and online subtitles, which are SRT or VTT) is drawn by the app from the cue text, so the subtitle style applies, as on iOS. Styled ASS/SSA and bitmap tracks (PGS, VobSub, DVB) stay with mpv, which keeps their own layout and styling. A codec that isn't named stays with mpv.
- **How it switches** (`MpvPlayerEngine`). The engine observes `sub-text` (the current cue's text). When the selected track is plain text it sets `sub-visibility` to `no`, so mpv doesn't draw the same line twice, and passes the cue text on through `PlayerEngine.subtitleText`. For every other track it sets `sub-visibility` back to `yes`. The selection changes at once when the viewer picks a track, without waiting for mpv's track list.
- **The style** (`player/SubtitleStyle.kt`). One JSON string under `sub.style`, with the iOS key names and ranges (size 12 to 72, box 0 to 0.9, distance 0 to 160), so a phone backup restores it. Empty or unreadable means the default look, as on iOS.
- **Drawing** (`player/ui/SubtitleLine.kt`). `SubtitleLine` draws one line: the colour, the edge (none, shadow, or outline as four offset copies), and the box. `SubtitleLayer` places it at the bottom and lifts it 112 dp while the controls show (iOS: `lift: showControls ? 112 : 0`). The overlay draws it above the picture and under the controls and panels, and it takes no input.
- **Settings** (`settings/SubtitleSettingsScreen.kt`). Appearance is the real editor now: size, colour, font, weight, edge, background, distance from the bottom, and reset. The preview calls the same `SubtitleLine` as the player. Sliders save when the drag ends, not on every move. The page reads the setting live, so a change made elsewhere shows up here.

### Where this differs from iOS
- Compose has no rounded font family, so "rounded" uses the sans-serif family. The other fonts map directly.
- Sizes are in the same units as iOS, but the player's controls are scaled by PLAYER_UI_SCALE and the subtitles are not, so the same size can read smaller next to the controls.
- ASS tracks keep mpv's styling, which is not the viewer's `sub.style`. As on iOS, that matches the file rather than the app.
- The settings preview draws plain text only. A styled ASS line appears only in the player.

### Not in 4.4c
- Style controls inside the player's subtitle panel (iOS has them there too; the setting is shared, so it's a small addition later).
- Android (Media3 has its own subtitle view; this needs its own pass).

### Check it
1. Play a file with an SRT track: the line is drawn in the app, and only once. Switch to an ASS track: mpv draws it, with its own styling. Switch back: the app draws again.
2. Settings -> Subtitles -> Appearance: change each control. The preview and the player's line change the same way. Leave and reopen the page: the values stay.
3. Show the controls during a line: the line rises above them. Hide them: it drops back to the distance you set.
4. Subtitle delay still moves the app-drawn line.
5. A phone backup restores the style with the iOS look.
6. If no plain-text line shows at all while mpv is hiding the track, the assumption that `sub-text` still updates while `sub-visibility` is `no` is wrong. Tell me, and I'll switch to a fallback that keeps mpv drawing and hides only its text.

### Not compiled
As before, the authoring environment has no Gradle or Kotlin compiler. Run `gradle :shared:desktopTest --tests "*SubtitleStyle*" --tests "*SubtitleRendering*"` first, then `gradle :desktopApp:run`.

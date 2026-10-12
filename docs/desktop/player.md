# Player (Batches 4.1 to 4.3a)

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
- Volume: mute button in the pill, slider slides out on hover. It is mpv's own volume (0..1).
- Auto-hide: 3.5 s after the last mouse movement, only while playing or buffering. Controls stay while paused, loading, ended, failed, dragging the seek bar, hovering the controls or using the volume slider. The cursor is hidden together with the controls (`hiddenPointerIcon`, desktop: blank AWT cursor).
- Mouse: click on the picture toggles play/pause, double-click toggles fullscreen. (iOS toggles the controls on tap; on desktop hover already does that.)
- The player is always dark (`PearDark`), whatever the app appearance.

### Glass setting (`PlayerLook`)

| `player.glass` | video | look |
| --- | --- | --- |
| off | any | **Flat**: the iOS flat fills (circle black 0.4 / accent 0.75, capsule 0.55, card 0.88) |
| on | paused, loading, error | **Blur**: the app's glass style (Haze) |
| on | playing | **Tint**: black tint + hairline edge, no blur, unless `desktop.playerBlurWhilePlaying` is on, then **Blur** |

The app-wide glass mode set to Flat also means Tint in the player. Settings -> Playback has the "Liquid Glass controls" switch and, under it, "Blur controls while playing" (desktop only, default off until the numbers are measured; see `phase0-decisions.md`).

### Not in 4.3a (by plan)

Resume / progress (4.3b), the pill with pinned and ordered options, speed, info card (4.3c; `actions` holds stand-in buttons for aspect, 4K mode and fullscreen until then), skip intro / up next (4.3d). Not planned: the "pear." loader animation and the paused-state show logo; the overlay shows a spinner instead.

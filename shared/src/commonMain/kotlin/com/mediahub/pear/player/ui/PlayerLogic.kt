package com.mediahub.pear.player.ui

import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.player.PlaybackState
import kotlin.math.abs
import kotlin.math.roundToLong

// Pure rules behind the player overlay (Batch 4.3a). No Compose in here, so they are unit tested.

/** How the overlay's surfaces are drawn right now. */
enum class PlayerLook {
    /** Haze blur over the video (the glass styles chosen in the glass settings). */
    Blur,

    /** Translucent tint with a hairline edge, no blur: cheap enough to sit over moving video. */
    Tint,

    /** The "Liquid Glass controls" switch is off: plain dark fills, the same as the iOS flat look. */
    Flat,
}

/**
 * Picks the look. Order of the fallbacks (from `phase0-decisions.md`): full blur, then tint-only while the
 * video moves, then flat. Blur over a *paused* frame costs nothing, so it is always allowed.
 *
 * @param glassOn the iOS `player.glass` switch ("Liquid Glass controls")
 * @param baseMode the app-wide glass style; [GlassMode.Flat] there means no blur anywhere
 * @param videoMoving true while frames are changing (playing)
 * @param blurWhilePlaying desktop-only switch `desktop.playerBlurWhilePlaying`
 */
fun playerLook(glassOn: Boolean, baseMode: GlassMode, videoMoving: Boolean, blurWhilePlaying: Boolean): PlayerLook = when {
    !glassOn -> PlayerLook.Flat
    baseMode == GlassMode.Flat -> PlayerLook.Tint
    videoMoving && !blurWhilePlaying -> PlayerLook.Tint
    else -> PlayerLook.Blur
}

/** Frames are changing: used for the look and for hiding the cursor. Buffering shows a frozen frame. */
fun isVideoMoving(state: PlaybackState): Boolean = state == PlaybackState.Playing

/** Seconds without mouse movement before the controls fade out (PlayerView.swift `scheduleHide`: 3.5 s). */
const val AUTO_HIDE_MILLIS = 3500L

/**
 * Whether the controls are on screen. They only ever hide while playing (or buffering) and untouched:
 * paused, loading, ended and failed all keep them up, and so do dragging the seek bar, hovering the controls
 * and an open volume slider.
 */
fun controlsVisible(
    state: PlaybackState,
    hasError: Boolean,
    recentlyActive: Boolean,
    scrubbing: Boolean,
    pointerOverControls: Boolean,
    volumeActive: Boolean,
    panelOpen: Boolean = false,
): Boolean {
    if (hasError || recentlyActive || scrubbing || pointerOverControls || volumeActive || panelOpen) return true
    return when (state) {
        PlaybackState.Playing, PlaybackState.Buffering -> false
        PlaybackState.Idle, PlaybackState.Loading, PlaybackState.Paused, PlaybackState.Ended, PlaybackState.Error -> true
    }
}

/** The play button shows pause while playback is running or only waiting for data. */
fun showsPauseSymbol(state: PlaybackState): Boolean = state == PlaybackState.Playing || state == PlaybackState.Buffering

/** The centre spinner: the source is opening or the player is waiting for data. */
fun isWaiting(state: PlaybackState): Boolean =
    state == PlaybackState.Loading || state == PlaybackState.Idle || state == PlaybackState.Buffering

/** Maths for the seek bar, kept apart so it can be tested. All fractions are 0..1 of the duration. */
object SeekMath {
    fun fraction(seconds: Double, duration: Double): Float =
        if (duration > 0.0 && seconds.isFinite()) (seconds / duration).coerceIn(0.0, 1.0).toFloat() else 0f

    fun timeAt(fraction: Float, duration: Double): Double =
        if (duration > 0.0) duration * fraction.coerceIn(0f, 1f) else 0.0

    /** The lighter segment runs from the playhead to the end of what is downloaded; never behind the playhead. */
    fun bufferedFraction(position: Double, buffered: Double, duration: Double): Float =
        fraction(maxOf(buffered, position), duration)

    /** "-41:20": time left, shown at the right end of the bar. */
    fun remainingLabel(shown: Double, duration: Double): String = "-" + Fmt.clock(maxOf(duration - shown, 0.0))

    /** Position after a ±[delta] skip, kept inside the file when the length is known. */
    fun skipped(position: Double, delta: Double, duration: Double): Double {
        val target = position + delta
        return if (duration > 0.0) target.coerceIn(0.0, duration) else maxOf(0.0, target)
    }
}

/** SF Symbol for the volume button. */
fun volumeSymbol(volume: Float): String = when {
    volume <= 0f -> "speaker.slash.fill"
    volume < 0.5f -> "speaker.wave.2.fill"
    else -> "speaker.wave.3.fill"
}

/** Mute toggles between silence and the last audible level ([lastAudible] falls back to full volume). */
fun toggledMute(current: Float, lastAudible: Float): Float =
    if (current > 0f) 0f else lastAudible.takeIf { it > 0.01f } ?: 1f

/** "S1 · E3 · Episode title" for an episode, the release info for a movie. */
fun playerSubtitleLine(season: Int?, episode: Int?, episodeTitle: String?, releaseInfo: String?): String? {
    if (season != null && episode != null) {
        val base = "S$season · E$episode"
        return if (episodeTitle.isNullOrBlank()) base else "$base · $episodeTitle"
    }
    return releaseInfo?.takeIf { it.isNotBlank() }
}

/** The panels the player can show over the video (Batch 4.3c). Only one is open at a time, as on iOS. */
enum class PlayerPanel {
    /** Playback speed, floating above the pill. The controls stay up. */
    Speed,

    /** Other streams for this title or episode. Takes the place of the controls. */
    Sources,

    /** The episode or movie card, centred on the screen. Takes the place of the controls. */
    Info,

    /** Aspect, the 4K performance mode and full screen, behind the pill's Display button. Floats above the pill. */
    Display,

    /** The subtitle tracks, delay and (later) online subtitles, on the right edge. Takes the place of the controls. */
    Subtitles,

    /** The audio tracks and audio delay, floating above the pill. */
    Audio,
}

/**
 * How much larger the player's controls and text are drawn than the window's own density (desktop). The video itself
 * is not scaled.
 */
const val PLAYER_UI_SCALE = 1.25f

/**
 * Sources, the info card and subtitles cover the controls (`showControls = false` in PlayerView.swift); speed, display and
 * audio float above them.
 */
fun panelCoversControls(panel: PlayerPanel?): Boolean =
    panel == PlayerPanel.Sources || panel == PlayerPanel.Info || panel == PlayerPanel.Subtitles

/**
 * Playback speed as the slider and the label see it (`setRate`, `rateText` in PlayerView.swift). The slider runs
 * 0.5x to 2x; values round to 0.01 and snap to exactly 1x near the middle.
 */
object SpeedMath {
    const val MIN = 0.5
    const val MAX = 2.0

    /** Slider position (0..1) to speed. */
    fun fromFraction(fraction: Float): Double {
        val v = (MIN + (MAX - MIN) * fraction.coerceIn(0f, 1f)).coerceIn(MIN, MAX)
        return if (abs(v - 1.0) < 0.025) 1.0 else (v * 100).roundToLong() / 100.0
    }

    /** Speed to slider position (0..1). */
    fun toFraction(rate: Double): Float = ((rate.coerceIn(MIN, MAX) - MIN) / (MAX - MIN)).toFloat()

    /** "1×", "0.5×", "1.25×": up to two decimals, no trailing zeros. */
    fun label(rate: Double): String {
        val hundredths = (rate * 100).roundToLong()
        val whole = hundredths / 100
        val frac = (hundredths % 100).toInt()
        val tail = when {
            frac == 0 -> ""
            frac % 10 == 0 -> ".${frac / 10}"
            else -> ".${frac.toString().padStart(2, '0')}"
        }
        return "$whole$tail×"
    }
}

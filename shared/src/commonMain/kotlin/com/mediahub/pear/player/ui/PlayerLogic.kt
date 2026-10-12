package com.mediahub.pear.player.ui

import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.player.PlaybackState

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
): Boolean {
    if (hasError || recentlyActive || scrubbing || pointerOverControls || volumeActive) return true
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

package com.mediahub.pear.player

import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.player.ui.PlayerLook
import com.mediahub.pear.player.ui.SeekMath
import com.mediahub.pear.player.ui.controlsVisible
import com.mediahub.pear.player.ui.isVideoMoving
import com.mediahub.pear.player.ui.playerLook
import com.mediahub.pear.player.ui.playerSubtitleLine
import com.mediahub.pear.player.ui.showsPauseSymbol
import com.mediahub.pear.player.ui.toggledMute
import com.mediahub.pear.player.ui.volumeSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLogicTest {
    // --- look ---

    @Test
    fun glassSwitchOffIsAlwaysFlat() {
        for (moving in listOf(true, false)) for (blur in listOf(true, false)) {
            assertEquals(PlayerLook.Flat, playerLook(false, GlassMode.Frosted, moving, blur))
        }
    }

    @Test
    fun movingVideoUsesTintUnlessBlurWhilePlayingIsOn() {
        assertEquals(PlayerLook.Tint, playerLook(true, GlassMode.Frosted, videoMoving = true, blurWhilePlaying = false))
        assertEquals(PlayerLook.Blur, playerLook(true, GlassMode.Frosted, videoMoving = true, blurWhilePlaying = true))
    }

    @Test
    fun pausedVideoAlwaysGetsBlur() {
        assertEquals(PlayerLook.Blur, playerLook(true, GlassMode.Glass, videoMoving = false, blurWhilePlaying = false))
    }

    @Test
    fun appWideFlatGlassMeansNoBlurInThePlayerEither() {
        assertEquals(PlayerLook.Tint, playerLook(true, GlassMode.Flat, videoMoving = false, blurWhilePlaying = true))
    }

    @Test
    fun onlyPlayingCountsAsMoving() {
        assertTrue(isVideoMoving(PlaybackState.Playing))
        for (s in PlaybackState.entries.filter { it != PlaybackState.Playing }) assertFalse(isVideoMoving(s), s.name)
    }

    // --- auto-hide ---

    private fun visible(
        state: PlaybackState,
        error: Boolean = false,
        active: Boolean = false,
        scrubbing: Boolean = false,
        over: Boolean = false,
        volume: Boolean = false,
    ) = controlsVisible(state, error, active, scrubbing, over, volume)

    @Test
    fun controlsHideOnlyWhilePlayingAndIdle() {
        assertFalse(visible(PlaybackState.Playing))
        assertFalse(visible(PlaybackState.Buffering))
        assertTrue(visible(PlaybackState.Playing, active = true))
    }

    @Test
    fun controlsStayWhilePausedLoadingEndedOrFailed() {
        for (s in listOf(PlaybackState.Paused, PlaybackState.Loading, PlaybackState.Idle, PlaybackState.Ended, PlaybackState.Error)) {
            assertTrue(visible(s), s.name)
        }
        assertTrue(visible(PlaybackState.Playing, error = true))
    }

    @Test
    fun scrubbingHoveringAndVolumeKeepControlsUp() {
        assertTrue(visible(PlaybackState.Playing, scrubbing = true))
        assertTrue(visible(PlaybackState.Playing, over = true))
        assertTrue(visible(PlaybackState.Playing, volume = true))
    }

    @Test
    fun pauseSymbolShowsForPlayingAndBuffering() {
        assertTrue(showsPauseSymbol(PlaybackState.Playing))
        assertTrue(showsPauseSymbol(PlaybackState.Buffering))
        assertFalse(showsPauseSymbol(PlaybackState.Paused))
        assertFalse(showsPauseSymbol(PlaybackState.Ended))
    }

    // --- seek bar ---

    @Test
    fun fractionIsClampedAndSafeWithoutDuration() {
        assertEquals(0.5f, SeekMath.fraction(30.0, 60.0))
        assertEquals(1f, SeekMath.fraction(90.0, 60.0))
        assertEquals(0f, SeekMath.fraction(-5.0, 60.0))
        assertEquals(0f, SeekMath.fraction(10.0, 0.0))
        assertEquals(0f, SeekMath.fraction(Double.NaN, 60.0))
    }

    @Test
    fun timeAtMapsFractionToSeconds() {
        assertEquals(30.0, SeekMath.timeAt(0.5f, 60.0))
        assertEquals(60.0, SeekMath.timeAt(2f, 60.0))
        assertEquals(0.0, SeekMath.timeAt(0.5f, 0.0))
    }

    @Test
    fun bufferedNeverEndsBehindThePlayhead() {
        assertEquals(0.5f, SeekMath.bufferedFraction(position = 30.0, buffered = 0.0, duration = 60.0))
        assertEquals(0.75f, SeekMath.bufferedFraction(position = 30.0, buffered = 45.0, duration = 60.0))
    }

    @Test
    fun remainingLabelCountsDown() {
        assertEquals("-0:30", SeekMath.remainingLabel(30.0, 60.0))
        assertEquals("-0:00", SeekMath.remainingLabel(70.0, 60.0))
        assertEquals("-1:00:00", SeekMath.remainingLabel(0.0, 3600.0))
    }

    @Test
    fun skipStaysInsideTheFile() {
        assertEquals(0.0, SeekMath.skipped(4.0, -10.0, 100.0))
        assertEquals(100.0, SeekMath.skipped(95.0, 10.0, 100.0))
        assertEquals(40.0, SeekMath.skipped(30.0, 10.0, 100.0))
        assertEquals(40.0, SeekMath.skipped(30.0, 10.0, 0.0)) // length unknown: only the lower bound applies
        assertEquals(0.0, SeekMath.skipped(3.0, -10.0, 0.0))
    }

    // --- volume ---

    @Test
    fun volumeSymbolFollowsLevel() {
        assertEquals("speaker.slash.fill", volumeSymbol(0f))
        assertEquals("speaker.wave.2.fill", volumeSymbol(0.3f))
        assertEquals("speaker.wave.3.fill", volumeSymbol(0.8f))
    }

    @Test
    fun muteRestoresTheLastAudibleLevel() {
        assertEquals(0f, toggledMute(0.6f, 0.6f))
        assertEquals(0.6f, toggledMute(0f, 0.6f))
        assertEquals(1f, toggledMute(0f, 0f))
    }

    // --- title line ---

    @Test
    fun subtitleLineForEpisodeAndMovie() {
        assertEquals("S1 · E3 · Pilot", playerSubtitleLine(1, 3, "Pilot", "2020"))
        assertEquals("S2 · E10", playerSubtitleLine(2, 10, "", "2020"))
        assertEquals("S2 · E10", playerSubtitleLine(2, 10, null, null))
        assertEquals("2021", playerSubtitleLine(null, null, null, "2021"))
        assertNull(playerSubtitleLine(null, null, null, null))
        assertNull(playerSubtitleLine(null, null, null, " "))
    }
}

package com.mediahub.pear.player.media

import com.mediahub.pear.player.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSessionRulesTest {

    @Test
    fun playingAndBufferingShowAsPlaying() {
        assertEquals(SystemPlayback.Playing, MediaSessionRules.status(PlaybackState.Playing))
        assertEquals(SystemPlayback.Playing, MediaSessionRules.status(PlaybackState.Buffering))
    }

    @Test
    fun loadingAndPausedShowAsPausedSoThePlayButtonIsOffered() {
        assertEquals(SystemPlayback.Paused, MediaSessionRules.status(PlaybackState.Paused))
        assertEquals(SystemPlayback.Paused, MediaSessionRules.status(PlaybackState.Loading))
    }

    @Test
    fun idleEndedAndFailedShowAsStopped() {
        assertEquals(SystemPlayback.Stopped, MediaSessionRules.status(PlaybackState.Idle))
        assertEquals(SystemPlayback.Stopped, MediaSessionRules.status(PlaybackState.Ended))
        assertEquals(SystemPlayback.Stopped, MediaSessionRules.status(PlaybackState.Error))
    }

    @Test
    fun playPauseToggles() {
        assertEquals(MediaEffect.TogglePlay, MediaSessionRules.effect(MediaCommand.PlayPause, nextAvailable = false))
    }

    @Test
    fun playAndPauseAreExplicit() {
        assertEquals(MediaEffect.Play, MediaSessionRules.effect(MediaCommand.Play, nextAvailable = false))
        assertEquals(MediaEffect.Pause, MediaSessionRules.effect(MediaCommand.Pause, nextAvailable = false))
    }

    @Test
    fun stopPausesAndKeepsThePlayerOpen() {
        assertEquals(MediaEffect.Pause, MediaSessionRules.effect(MediaCommand.Stop, nextAvailable = true))
    }

    @Test
    fun nextPlaysTheNextEpisodeOnlyWhenThereIsOne() {
        assertEquals(MediaEffect.PlayNext, MediaSessionRules.effect(MediaCommand.Next, nextAvailable = true))
        assertEquals(MediaEffect.None, MediaSessionRules.effect(MediaCommand.Next, nextAvailable = false))
    }

    @Test
    fun previousGoesBackToTheStartOfTheTitle() {
        assertEquals(MediaEffect.RestartTitle, MediaSessionRules.effect(MediaCommand.Previous, nextAvailable = true))
        assertEquals(MediaEffect.RestartTitle, MediaSessionRules.effect(MediaCommand.Previous, nextAvailable = false))
    }
}

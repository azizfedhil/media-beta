package com.mediahub.pear.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAwakeRulesTest {

    @Test
    fun theScreenStaysAwakeWhileTheStreamOpensOrPlays() {
        assertTrue(KeepAwakeRules.wanted(PlaybackState.Loading, error = null))
        assertTrue(KeepAwakeRules.wanted(PlaybackState.Buffering, error = null))
        assertTrue(KeepAwakeRules.wanted(PlaybackState.Playing, error = null))
    }

    @Test
    fun theScreenIsFreeWhilePausedEndedIdleOrFailed() {
        assertFalse(KeepAwakeRules.wanted(PlaybackState.Paused, error = null), "as iOS: a paused player lets the screen lock")
        assertFalse(KeepAwakeRules.wanted(PlaybackState.Ended, error = null))
        assertFalse(KeepAwakeRules.wanted(PlaybackState.Idle, error = null))
        assertFalse(KeepAwakeRules.wanted(PlaybackState.Error, error = null))
    }

    @Test
    fun anErrorMessageEndsTheHoldEvenWhilePlaying() {
        assertFalse(KeepAwakeRules.wanted(PlaybackState.Playing, error = "Could not play this source."), "as iOS: no error, no hold")
    }
}

package com.mediahub.pear.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerEngineTest {

    @Test
    fun dummyPlayerEngineInitialState() = runTest {
        val engine = DummyPlayerEngine()
        assertEquals(PlaybackState.Idle, engine.state.value)
        assertEquals(0.0, engine.positionSeconds.value)
        assertEquals(0.0, engine.durationSeconds.value)
        assertEquals(1.0, engine.playbackRate.value)
        assertEquals(1.0f, engine.volume.value)
        assertNull(engine.errorMessage.value)
    }

    @Test
    fun dummyPlayerEngineReportsErrorOnLoad() = runTest {
        val engine = DummyPlayerEngine()
        engine.load("https://example.com/stream.mp4")
        assertEquals(PlaybackState.Error, engine.state.value)
        assertEquals("Playback engine is unavailable.", engine.errorMessage.value)
    }

    @Test
    fun dummyPlayerEnginePreservesInitialError() = runTest {
        val engine = DummyPlayerEngine("libmpv library not found.")
        assertEquals(PlaybackState.Error, engine.state.value)
        assertEquals("libmpv library not found.", engine.errorMessage.value)
    }
}
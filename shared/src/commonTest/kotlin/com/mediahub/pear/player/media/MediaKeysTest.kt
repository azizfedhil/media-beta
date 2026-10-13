package com.mediahub.pear.player.media

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaKeysTest {

    @Test
    fun theMediaKeysSendTheirCommands() {
        assertEquals(MediaCommand.PlayPause, MediaKeys.command(Key.MediaPlayPause))
        assertEquals(MediaCommand.Play, MediaKeys.command(Key.MediaPlay))
        assertEquals(MediaCommand.Pause, MediaKeys.command(Key.MediaPause))
        assertEquals(MediaCommand.Stop, MediaKeys.command(Key.MediaStop))
        assertEquals(MediaCommand.Next, MediaKeys.command(Key.MediaNext))
        assertEquals(MediaCommand.Previous, MediaKeys.command(Key.MediaPrevious))
    }

    @Test
    fun ordinaryKeysAreNotMediaKeys() {
        assertNull(MediaKeys.command(Key.Spacebar), "Space is the player's own play/pause, not a media key")
        assertNull(MediaKeys.command(Key.DirectionLeft))
        assertNull(MediaKeys.command(Key.F))
    }
}

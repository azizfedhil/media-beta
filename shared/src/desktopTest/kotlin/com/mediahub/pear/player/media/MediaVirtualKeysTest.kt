package com.mediahub.pear.player.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaVirtualKeysTest {

    @Test
    fun theWindowsMediaKeyCodesSendTheirCommands() {
        // winuser.h: VK_MEDIA_NEXT_TRACK 0xB0, VK_MEDIA_PREV_TRACK 0xB1, VK_MEDIA_STOP 0xB2, VK_MEDIA_PLAY_PAUSE 0xB3.
        assertEquals(MediaCommand.Next, MediaVirtualKeys.command(0xB0))
        assertEquals(MediaCommand.Previous, MediaVirtualKeys.command(0xB1))
        assertEquals(MediaCommand.Stop, MediaVirtualKeys.command(0xB2))
        assertEquals(MediaCommand.PlayPause, MediaVirtualKeys.command(0xB3))
    }

    @Test
    fun theSessionRegistersExactlyTheFourMediaKeys() {
        assertEquals(4, MediaVirtualKeys.keys.size)
        assertEquals(MediaVirtualKeys.keys.size, MediaVirtualKeys.keys.toSet().size, "each key has its own hot key id")
        assertNull(MediaVirtualKeys.command(0x41), "the A key is not a media key")
    }
}

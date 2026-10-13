package com.mediahub.pear.player

import androidx.compose.ui.input.key.Key
import com.mediahub.pear.player.ui.PlayerKeyAction
import com.mediahub.pear.player.ui.PlayerKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerKeysTest {

    @Test
    fun theKeysTheSpecNamesDoTheirJobs() {
        assertEquals(PlayerKeyAction.TogglePlay, PlayerKeys.action(Key.Spacebar))
        assertEquals(PlayerKeyAction.SeekBack, PlayerKeys.action(Key.DirectionLeft))
        assertEquals(PlayerKeyAction.SeekForward, PlayerKeys.action(Key.DirectionRight))
        assertEquals(PlayerKeyAction.VolumeUp, PlayerKeys.action(Key.DirectionUp))
        assertEquals(PlayerKeyAction.VolumeDown, PlayerKeys.action(Key.DirectionDown))
        assertEquals(PlayerKeyAction.Mute, PlayerKeys.action(Key.M))
        assertEquals(PlayerKeyAction.Fullscreen, PlayerKeys.action(Key.F))
    }

    @Test
    fun keysTheSpecDoesNotNameDoNothing() {
        assertNull(PlayerKeys.action(Key.A))
        assertNull(PlayerKeys.action(Key.Escape), "Escape belongs to the shell: it leaves full screen, then the player")
        assertNull(PlayerKeys.action(Key.One), "the iOS player has no chapters, so the number keys do nothing")
    }

    @Test
    fun theShellsShortcutsAreNeverTakenByThePlayer() {
        assertNull(PlayerKeys.action(Key.F, shellModifier = true), "Ctrl+F is the shell's search")
        assertNull(PlayerKeys.action(Key.DirectionLeft, shellModifier = true), "Alt+Left is the shell's back")
    }

    @Test
    fun shiftMakesASeekLonger() {
        assertEquals(10.0, PlayerKeys.seekSeconds(shift = false))
        assertEquals(30.0, PlayerKeys.seekSeconds(shift = true))
    }

    @Test
    fun theWheelRaisesTheVolumeWhenItScrollsUp() {
        assertEquals(PlayerKeys.VOLUME_STEP, PlayerKeys.wheelStep(-1f))
        assertEquals(-PlayerKeys.VOLUME_STEP, PlayerKeys.wheelStep(1f))
        assertEquals(0f, PlayerKeys.wheelStep(0f))
    }
}

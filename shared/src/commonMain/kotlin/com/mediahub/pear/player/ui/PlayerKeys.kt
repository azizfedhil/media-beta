package com.mediahub.pear.player.ui

import androidx.compose.ui.input.key.Key

/** What a key does in the player (Batch 4.6a). Spec: none, the iOS player has no key commands; these are the desktop's own. */
enum class PlayerKeyAction { TogglePlay, SeekBack, SeekForward, VolumeUp, VolumeDown, Mute, Fullscreen }

/**
 * The player's keyboard and wheel, as numbers and actions with no window in them, so they are unit tested.
 *
 * Space pauses; the left and right arrows seek by 10 s (Shift: 30 s), as the buttons do; the up and down arrows set the
 * volume; M mutes; F goes full screen. Escape is not here: the shell takes it first (it leaves full screen, then the
 * player). Ctrl, Alt and Meta belong to the shell (Ctrl+1 to 4, Ctrl+F, Alt+Left), so the player never takes those keys.
 */
object PlayerKeys {
    const val SEEK_SECONDS = 10.0
    const val SEEK_SECONDS_LARGE = 30.0
    const val VOLUME_STEP = 0.05f

    /** The action for [key], or null when the player doesn't use it. */
    fun action(key: Key, shift: Boolean = false, shellModifier: Boolean = false): PlayerKeyAction? {
        if (shellModifier) return null
        return when (key) {
            Key.Spacebar -> PlayerKeyAction.TogglePlay
            Key.DirectionLeft -> PlayerKeyAction.SeekBack
            Key.DirectionRight -> PlayerKeyAction.SeekForward
            Key.DirectionUp -> PlayerKeyAction.VolumeUp
            Key.DirectionDown -> PlayerKeyAction.VolumeDown
            Key.M -> PlayerKeyAction.Mute
            Key.F -> PlayerKeyAction.Fullscreen
            else -> null
        }
    }

    /** How far a seek key moves, in seconds. */
    fun seekSeconds(shift: Boolean): Double = if (shift) SEEK_SECONDS_LARGE else SEEK_SECONDS

    /**
     * How much one wheel step changes the volume: scrolling up (a negative y, as on most mice) raises it, down lowers it, and
     * no scroll changes nothing.
     */
    fun wheelStep(dy: Float): Float = when {
        dy < 0f -> VOLUME_STEP
        dy > 0f -> -VOLUME_STEP
        else -> 0f
    }
}

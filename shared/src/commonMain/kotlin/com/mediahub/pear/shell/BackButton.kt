package com.mediahub.pear.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput

/*
 * The mouse back button (Batch 4.6c). Spec: none. The iOS app's back is the edge swipe, which is touch-only (Phase 5.3).
 * The back button does what Escape does, so the two keys agree everywhere.
 */

/** Tells a press from a hold: [pressed] is true once for each press, on the change from up to down. */
class BackButtonEdge {
    private var down = false

    fun pressed(isDown: Boolean): Boolean {
        val edge = isDown && !down
        down = isDown
        return edge
    }
}

/** Back, as Escape does it: a full-screen player leaves full screen first, then the screen goes back. */
fun backFromMouse(shell: ShellState): Boolean = shell.escapeHook?.invoke() == true || shell.back()

/**
 * Runs [backFromMouse] when the mouse's back button is pressed. It reads the Initial pass, so it sees each press before the
 * screen under the pointer does, and it consumes nothing.
 */
fun Modifier.mouseBack(shell: ShellState): Modifier = pointerInput(shell) {
    awaitPointerEventScope {
        val edge = BackButtonEdge()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (edge.pressed(event.buttons.isBackPressed)) backFromMouse(shell)
        }
    }
}

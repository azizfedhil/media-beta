package com.mediahub.pear.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/*
 * The mouse back button (Batch 4.6c). Spec: none. The iOS app's back is the edge swipe, which is touch-only (Phase 5.3).
 * The back button does what Escape does, so the two keys agree everywhere.
 */

/** Whether [event] is the mouse's back button going down. The platform reads it (see BackButton.desktop.kt). */
internal expect fun isBackButtonPressed(event: PointerEvent): Boolean

/** Back, as Escape does it: a full-screen player leaves full screen first, then the screen goes back. */
fun backFromMouse(shell: ShellState): Boolean = shell.escape()

/**
 * Runs [backFromMouse] when the mouse's back button is pressed. It reads the Initial pass, so it sees each press before the
 * screen under the pointer does, and it consumes nothing.
 */
fun Modifier.mouseBack(shell: ShellState): Modifier = pointerInput(shell) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (isBackButtonPressed(event)) backFromMouse(shell)
        }
    }
}

package com.mediahub.pear.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/** How far a press may move before it counts as a drag (along a row, say) rather than a click. */
private const val CLICK_SLOP_PX = 12f

/**
 * A left click runs [onClick]; a right click runs [onMenu]. Both fire on release. A press that moves past the slop is a
 * drag and fires neither, so dragging a shelf still scrolls it. [source] receives the press, so pressed-state visuals work.
 */
@Composable
fun Modifier.clickOrMenu(
    source: MutableInteractionSource? = null,
    onClick: () -> Unit,
    onMenu: () -> Unit,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val menu by rememberUpdatedState(onMenu)
    return this.pointerInput(source) {
        awaitEachGesture {
            // The first press (not a move or hover) says which button went down.
            var down: PointerEvent = awaitPointerEvent()
            while (down.type != PointerEventType.Press) down = awaitPointerEvent()
            val fromSecondary = down.buttons.isSecondaryPressed
            val start = down.changes.first().position
            val press = PressInteraction.Press(start)
            source?.tryEmit(press)

            var release: PointerInputChange? = null
            var moved = false
            while (release == null && !moved) {
                val change = awaitPointerEvent().changes.first()
                if (!change.pressed) {
                    release = change
                } else if ((change.position - start).getDistance() > CLICK_SLOP_PX) {
                    moved = true
                }
            }
            if (release != null && !moved) {
                source?.tryEmit(PressInteraction.Release(press))
                release.consume()
                if (fromSecondary) menu() else click()
            } else {
                source?.tryEmit(PressInteraction.Cancel(press))
            }
        }
    }
}

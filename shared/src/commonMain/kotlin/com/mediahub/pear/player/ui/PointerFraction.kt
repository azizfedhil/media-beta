package com.mediahub.pear.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Horizontal press-drag-release over the element, reported as a 0..1 fraction of its width, plus hover
 * (a pointer that is over it without a button down). One handler for the seek bar and the volume slider.
 *
 * - [onHover]: fraction under a hovering pointer, `null` when it leaves.
 * - [onStart] / [onMove] / [onEnd]: press, drag (also outside the element's bounds) and release.
 *
 * Works for mouse and touch alike (it looks at `pressed` / `previousPressed`, not at buttons).
 */
@Composable
internal fun Modifier.fractionPointer(
    enabled: Boolean = true,
    onHover: (Float?) -> Unit = {},
    onStart: (Float) -> Unit = {},
    onMove: (Float) -> Unit = {},
    onEnd: (Float) -> Unit = {},
): Modifier {
    val hover = rememberUpdatedState(onHover)
    val start = rememberUpdatedState(onStart)
    val move = rememberUpdatedState(onMove)
    val end = rememberUpdatedState(onEnd)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                when {
                    !dragging && change.pressed && !change.previousPressed -> {
                        dragging = true
                        change.consume()
                        start.value(fraction)
                    }
                    dragging && change.pressed -> {
                        change.consume()
                        move.value(fraction)
                    }
                    dragging -> {
                        dragging = false
                        change.consume()
                        end.value(fraction)
                    }
                    event.type == PointerEventType.Exit -> hover.value(null)
                    else -> hover.value(fraction)
                }
            }
        }
    }
}

package com.mediahub.pear.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull

/** How far a mouse press may move before it counts as a drag (along a row, say) rather than a click. */
private const val CLICK_SLOP_PX = 12f

/**
 * Whether the right (secondary) mouse button is the one that went down in [event]. The pointer API exposes this only on
 * some platforms, so each platform answers it (see `ClicksDesktop.kt`).
 */
internal expect fun isSecondaryButtonDown(event: PointerEvent): Boolean

/** What a touch that went down turned into. */
private sealed interface TouchOutcome {
    /** Lifted inside the slop, before the long-press time. [release] is the lifting change. */
    class Tap(val release: PointerInputChange) : TouchOutcome

    /** Moved past the slop before the long-press time: a drag, which fires nothing. */
    data object Dragged : TouchOutcome

    /** Still down, inside the slop, when the long-press time passed. */
    data object LongPress : TouchOutcome
}

/**
 * A left click runs [onClick]; a right click runs [onMenu]. Both fire on release. A press that moves past the slop is a
 * drag and fires neither, so dragging a shelf still scrolls it. A touch held for the long-press time runs [onMenu] instead,
 * with a haptic tick, and the rest of that gesture is swallowed. [source] receives the press, so pressed-state visuals work.
 *
 * A mouse press also counts as a drag as soon as a parent (a row being dragged) consumes the move, even if the pointer
 * stayed near its start inside the card.
 */
@Composable
fun Modifier.clickOrMenu(
    source: MutableInteractionSource? = null,
    onClick: () -> Unit,
    onMenu: () -> Unit,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val menu by rememberUpdatedState(onMenu)
    val haptic = LocalHapticFeedback.current
    val hapticNow by rememberUpdatedState(haptic)
    return this.pointerInput(source) {
        val viewConfig = viewConfiguration
        awaitEachGesture {
            // The first press (not a move or hover) says which button went down.
            var down: PointerEvent = awaitPointerEvent()
            while (down.type != PointerEventType.Press) down = awaitPointerEvent()
            val first = down.changes.first()
            val fromSecondary = isSecondaryButtonDown(down)
            val start = first.position
            val press = PressInteraction.Press(start)
            source?.tryEmit(press)

            // Stylus counts as touch. Anything else (a mouse, a trackpad) keeps the original path below.
            val touch = first.type == PointerType.Touch || first.type == PointerType.Stylus
            if (touch) {
                when (val outcome = awaitTouchOutcome(start, viewConfig.touchSlop, viewConfig.longPressTimeoutMillis)) {
                    is TouchOutcome.Tap -> {
                        source?.tryEmit(PressInteraction.Release(press))
                        outcome.release.consume()
                        click()
                    }
                    TouchOutcome.Dragged -> source?.tryEmit(PressInteraction.Cancel(press))
                    TouchOutcome.LongPress -> {
                        source?.tryEmit(PressInteraction.Cancel(press))
                        hapticNow.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                        swallowUntilUp()
                    }
                }
            } else {
                var release: PointerInputChange? = null
                var moved = false
                while (release == null && !moved) {
                    // The Final pass runs after every parent has had its turn, so a row that took this drag has
                    // already consumed it by now.
                    val change = awaitPointerEvent(PointerEventPass.Final).changes.first()
                    when {
                        !change.pressed -> release = change
                        // Dragging a shelf moves the card along with the cursor, so the distance measured inside the
                        // card barely grows. The parent consuming the move is the reliable sign of a drag.
                        change.isConsumed && change.position != change.previousPosition -> moved = true
                        (change.position - start).getDistance() > CLICK_SLOP_PX -> moved = true
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
}

/**
 * Decides what a touch that went down at [start] turned into. A release comes before movement, as it does for the mouse;
 * staying inside [slop] until [longPressMillis] passes is a long press.
 */
private suspend fun AwaitPointerEventScope.awaitTouchOutcome(
    start: Offset,
    slop: Float,
    longPressMillis: Long,
): TouchOutcome {
    val decided = withTimeoutOrNull(longPressMillis) {
        var outcome: TouchOutcome? = null
        while (outcome == null) {
            val change = awaitPointerEvent().changes.first()
            outcome = when {
                !change.pressed -> TouchOutcome.Tap(change)
                (change.position - start).getDistance() > slop -> TouchOutcome.Dragged
                else -> null
            }
        }
        outcome
    }
    return decided ?: TouchOutcome.LongPress
}

/**
 * After a long press the finger may still be down. Consume every change until no pointer is down, so lifting the finger
 * neither clicks nor starts a scroll.
 */
private suspend fun AwaitPointerEventScope.swallowUntilUp() {
    var event: PointerEvent
    do {
        event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

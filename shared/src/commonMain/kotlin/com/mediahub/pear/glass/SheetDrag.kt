package com.mediahub.pear.glass

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How a phone bottom sheet is pulled. [offsetPx] is positive when the sheet is pulled down. A pull down past the dismiss
 * distance, or a fling down, closes it. A pull up past the expand distance, or a fling up, grows its list, when it can.
 * Anything else springs back to rest. Build one with [rememberSheetDrag].
 */
class SheetDragState internal constructor(
    /** Whether a pull up can grow the sheet's list. */
    val expandable: Boolean,
    private val scope: CoroutineScope,
    private val onDismissState: State<() -> Unit>,
    private val dismissPx: Float,
    private val expandPx: Float,
    private val flingPx: Float,
    private val maxUpPx: Float,
) {
    /** How far the sheet is pulled, in px. Positive is down. */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** True while an expandable sheet shows its taller list. */
    var expanded by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    /**
     * Where the sheet is drawn. A pull up is damped to a fifth, with at most 24 dp of travel, so the list grows on release
     * instead of the sheet moving.
     */
    val visualOffset: Float
        get() = if (offsetPx >= 0f) offsetPx else maxOf(offsetPx * 0.2f, -maxUpPx)

    /** Follows the finger. Cancels any settling that is still running. */
    fun dragBy(dy: Float) {
        settleJob?.cancel()
        offsetPx += dy
    }

    /** Decides what a release does. [velocityY] is in px per second, positive down. */
    fun settle(velocityY: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val pulledDown = offsetPx > dismissPx || velocityY > flingPx
            val pulledUp = offsetPx < -expandPx || velocityY < -flingPx
            when {
                pulledDown && expanded -> {
                    expanded = false
                    springToRest()
                }
                // The offset is left where the drag ended, so the exit animation starts from there.
                pulledDown -> onDismissState.value.invoke()
                pulledUp && expandable -> {
                    expanded = true
                    springToRest()
                }
                else -> springToRest()
            }
        }
    }

    private suspend fun springToRest() {
        animate(offsetPx, 0f, animationSpec = spring(dampingRatio = 0.85f)) { value, _ -> offsetPx = value }
    }

    /** Takes the scroll of the sheet's content: a pull back toward rest comes first, then a pull up can grow the sheet. */
    val nestedConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            var used = 0f
            // Moving back toward rest: take the movement up to 0. The rest goes on to the content.
            if ((offsetPx > 0f && dy < 0f) || (offsetPx < 0f && dy > 0f)) {
                used = if (offsetPx > 0f) maxOf(dy, -offsetPx) else minOf(dy, -offsetPx)
                dragBy(used)
            }
            // Pulling up on a sheet that can still grow: the sheet takes the rest before the list scrolls.
            val rest = dy - used
            if (rest < 0f && expandable && !expanded) {
                dragBy(rest)
                used += rest
            }
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Pulled down past the top of the content: the sheet takes what the content could not.
            if (source == NestedScrollSource.Drag && available.y > 0f) {
                dragBy(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offsetPx != 0f) {
                settle(available.y)
                return available
            }
            return Velocity.Zero
        }
    }
}

/**
 * The drag state of one sheet. [expandable] says whether a pull up grows its list. [onDismiss] runs when the sheet is pulled
 * away. It is read through rememberUpdatedState, so a new lambda is picked up without rebuilding the state.
 */
@Composable
fun rememberSheetDrag(expandable: Boolean, onDismiss: () -> Unit): SheetDragState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    return remember(expandable, density) {
        with(density) {
            SheetDragState(
                expandable = expandable,
                scope = scope,
                onDismissState = currentOnDismiss,
                dismissPx = 96.dp.toPx(),
                expandPx = 56.dp.toPx(),
                flingPx = 800.dp.toPx(),
                maxUpPx = 24.dp.toPx(),
            )
        }
    }
}

/** Moves a sheet with its drag, and takes the scroll of its content. Put it on the sheet's outer modifier. */
fun Modifier.sheetDragOffset(state: SheetDragState): Modifier =
    this.graphicsLayer { translationY = state.visualOffset }.nestedScroll(state.nestedConnection)

/** Dragging the grabber moves the sheet. Released, it settles (see [SheetDragState.settle]). */
@Composable
fun Modifier.sheetHandleDrag(state: SheetDragState): Modifier =
    this.draggable(
        state = rememberDraggableState { state.dragBy(it) },
        orientation = Orientation.Vertical,
        onDragStopped = { state.settle(it) },
    )

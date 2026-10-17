package com.mediahub.pear.shell

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** A LazyRow you can also scroll by holding the mouse button and dragging (desktop LazyRows only react to the wheel). */
@Composable
fun DragLazyRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    /** Where the row is scrolled. Pass one to scroll it from outside. Placed before [content] so trailing-lambda calls still compile. */
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> scope.launch { state.scrollBy(-delta) } }
    // Rows on the wide Home feed tell the shell how far they've been dragged (it folds the sidebar and hero away).
    val feed = LocalFeedCollapse.current
    if (feed != null) {
        val id = remember { Any() }
        LaunchedEffect(state) {
            snapshotFlow {
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.index * it.size + state.firstVisibleItemScrollOffset } ?: 0
            }.collect { feed.report(id, it.toFloat()) }
        }
        DisposableEffect(id) { onDispose { feed.forget(id) } }
    }
    LazyRow(
        modifier = modifier.draggable(drag, Orientation.Horizontal).shiftWheelHorizontal { state.scrollBy(it) },
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/**
 * A [LazyHorizontalGrid] with the same drag-to-scroll as [DragLazyRow]: [rows] stacked, the items fill down each
 * column and then across. The caller must give it a height (a horizontal grid can't size itself across a vertical
 * scroll).
 */
@Composable
fun DragLazyGrid(
    rows: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit,
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> scope.launch { state.scrollBy(-delta) } }
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows),
        modifier = modifier.draggable(drag, Orientation.Horizontal).shiftWheelHorizontal { state.scrollBy(it) },
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * Android: a mouse wheel with Shift held, or a horizontal trackpad swipe, scrolls a horizontal row. A plain wheel is left
 * alone, so it still scrolls the page. A change something below already consumed is skipped, so nothing moves twice.
 * Desktop returns the modifier unchanged.
 */
fun Modifier.shiftWheelHorizontal(scrollBy: suspend (Float) -> Unit): Modifier =
    if (!PlatformCaps.isTouchFirst) this else pointerInput(scrollBy) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed || change.type != PointerType.Mouse) continue
                val delta = change.scrollDelta
                val along = when {
                    event.keyboardModifiers.isShiftPressed -> delta.y // Shift + wheel
                    delta.y == 0f -> delta.x // a horizontal trackpad swipe
                    else -> 0f // a plain wheel scrolls the page
                }
                if (along == 0f) continue
                change.consume()
                // The callers' lambdas hit restricted suspending functions (LazyListState.scrollBy and friends), which the
                // compiler only allows to run on a context inherited directly from this awaitPointerEventScope. A plain
                // launch{} would install a fresh Job in that context and trip ILLEGAL_RESTRICTED_SUSPENDING_FUNCTION_CALL,
                // so start UNDISPATCHED: the lambda runs synchronously here, keeps this scope's context, and nothing else changes.
                launch(start = CoroutineStart.UNDISPATCHED) { scrollBy(along * 40f) }
            }
        }
    }

/**
 * Makes a Row (or any horizontal content) scroll by the wheel, by touch, and by holding the mouse button and dragging. A plain
 * `horizontalScroll` takes the wheel only, so a row of chips wider than the window could not be pulled along on desktop. The
 * outer drag takes a mouse drag; touch is consumed by the inner scroller first, as in [DragLazyRow].
 */
@Composable
fun Modifier.dragHorizontalScroll(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> scope.launch { state.scrollBy(-delta) } }
    return this.draggable(drag, Orientation.Horizontal).horizontalScroll(state).shiftWheelHorizontal { state.scrollBy(it) }
}

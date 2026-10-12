package com.mediahub.pear.shell

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A LazyRow you can also scroll by holding the mouse button and dragging (desktop LazyRows only react to the wheel). */
@Composable
fun DragLazyRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
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
        modifier = modifier.draggable(drag, Orientation.Horizontal),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

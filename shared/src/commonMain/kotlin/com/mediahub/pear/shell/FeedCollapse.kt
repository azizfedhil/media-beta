package com.mediahub.pear.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * How far the Home feed has been scrolled away from its start, 0..1. Vertical scroll and horizontal row drags both
 * count. The wide Home layout uses it to shrink the sidebar to icons and fold the hero panel away.
 */
class FeedCollapse {
    var vertical by mutableFloatStateOf(0f)
    private val rows = mutableStateMapOf<Any, Float>()

    fun report(id: Any, px: Float) { rows[id] = px }
    fun forget(id: Any) { rows.remove(id) }

    /** Opens the hero again: forgets the row drags and the vertical position, so the progress goes back to 0. */
    fun expand() {
        rows.clear()
        vertical = 0f
    }

    val horizontal: Float get() = ((rows.values.maxOrNull() ?: 0f) / 360f).coerceIn(0f, 1f)
    val progress: Float get() = maxOf(vertical, horizontal)
}

val LocalFeedCollapse = compositionLocalOf<FeedCollapse?> { null }

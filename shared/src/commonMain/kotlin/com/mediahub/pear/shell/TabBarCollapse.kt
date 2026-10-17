package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/**
 * Folds the phone tab bar into one round button while the person scrolls down, and opens it again on a small scroll up
 * (iOS `.tabBarMinimizeBehavior(.onScrollDown)`). It only watches the scroll and never consumes any of it, so the pages
 * scroll as they did. Horizontal rows move no vertical distance, so they are ignored.
 */
class TabBarCollapse(private val shell: ShellState, private val thresholdPx: Float) : NestedScrollConnection {
    /** Vertical movement since the last change of direction. Negative is scrolling down. */
    private var acc = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = consumed.y
        if (dy == 0f) return Offset.Zero
        if (dy.sign != acc.sign) acc = 0f
        acc += dy
        if (acc <= -thresholdPx) shell.tabBarMinimized = true
        else if (acc >= thresholdPx / 2) shell.tabBarMinimized = false
        return Offset.Zero
    }
}

/** A [TabBarCollapse] for [shell], kept across recomposition. The threshold is 48 dp of scroll. */
@Composable
fun rememberTabBarCollapse(shell: ShellState): TabBarCollapse {
    val thresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    return remember(shell, thresholdPx) { TabBarCollapse(shell, thresholdPx) }
}

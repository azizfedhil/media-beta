package com.mediahub.pear.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** How the desktop window is shown. Maximized and full screen are the window's own states, not a size. */
enum class WindowMode { Windowed, Maximized, Fullscreen }

/** Lets shared code resize the window and change its state (desktop only). The dev "phone window" toggle uses it. */
interface WindowControl {
    val size: DpSize
    fun resize(width: Dp, height: Dp)

    /**
     * The window's state. Reading it gives the state now; setting it puts the window in that state. The player reads it
     * on entry and sets it back on leaving, so a maximized or full-screen app stays that way after a film.
     */
    var mode: WindowMode
        get() = WindowMode.Windowed
        set(_) {}
}

val LocalWindowControl = staticCompositionLocalOf<WindowControl?> { null }

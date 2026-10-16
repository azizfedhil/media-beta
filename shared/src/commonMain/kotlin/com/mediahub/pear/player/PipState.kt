package com.mediahub.pear.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Picture in Picture for the player (E6). The platform opens the window (see [openWindow]) and reports that it is open
 * ([active]). The player says when leaving the app may open one ([wanted]) and what shape the picture has. Desktop has no
 * window of this kind, so nothing here is set there.
 */
object PipState {
    /** The window is open: the player shows the picture alone, without its controls or panels. */
    var active by mutableStateOf(false)

    /** The player is on screen and playing, with Picture in Picture allowed in Settings. */
    var wanted by mutableStateOf(false)

    /** The picture's width over its height, so the window keeps the film's shape. */
    var videoAspect by mutableStateOf(16.0 / 9.0)

    /** Set by the platform: opens the window now. Null where there is none. */
    var openWindow: (() -> Unit)? = null

    fun request() {
        openWindow?.invoke()
    }
}

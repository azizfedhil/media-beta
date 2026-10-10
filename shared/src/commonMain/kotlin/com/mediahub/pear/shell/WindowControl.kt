package com.mediahub.pear.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** Lets shared code resize the window (desktop only). The dev "phone window" toggle uses it. */
interface WindowControl {
    val size: DpSize
    fun resize(width: Dp, height: Dp)
}

val LocalWindowControl = staticCompositionLocalOf<WindowControl?> { null }

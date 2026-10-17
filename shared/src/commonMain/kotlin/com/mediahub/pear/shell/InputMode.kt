package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The input the person used last: a mouse, or a finger or stylus. Android reports every pointer as touch, so the shell
 * writes this from the pointer events it sees (see AdaptiveShell). Read through [touchStyle].
 */
object InputMode {
    var mouseActive by mutableStateOf(false)
}

/**
 * True when the screen should follow touch rules (a tap shows the controls, a click on a row opens the details page).
 * A mouse on a wide layout (a tablet with a keyboard and mouse) follows the desktop rules instead. Desktop is always false
 * here, because its [PlatformCaps.isTouchFirst] is false.
 */
@Composable
fun touchStyle(): Boolean = PlatformCaps.isTouchFirst && !(InputMode.mouseActive && LocalWideLayout.current)

package com.mediahub.pear.shell

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed

/** Android: the secondary button of a mouse or trackpad. Touch has none, so a finger is never a right click. */
internal actual fun isSecondaryButtonDown(event: PointerEvent): Boolean =
    event.buttons.isSecondaryPressed

package com.mediahub.pear.shell

import androidx.compose.ui.input.pointer.PointerEvent

/** Android: the back action comes as a key event and is handled by [SystemBackHandler]. A pointer never counts as it. */
internal actual fun isBackButtonPressed(event: PointerEvent): Boolean = false

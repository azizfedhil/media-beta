package com.mediahub.pear.player.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** Android has no cursor to hide on a touch screen: the default icon stands. */
internal actual fun hiddenPointerIcon(): PointerIcon = PointerIcon.Default

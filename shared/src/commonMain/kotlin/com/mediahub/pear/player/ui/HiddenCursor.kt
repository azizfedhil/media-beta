package com.mediahub.pear.player.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** A mouse cursor that draws nothing, for the player while the controls are hidden. Falls back to the default arrow. */
internal expect fun hiddenPointerIcon(): PointerIcon

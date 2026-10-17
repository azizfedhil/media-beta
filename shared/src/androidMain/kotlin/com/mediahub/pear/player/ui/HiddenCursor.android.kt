package com.mediahub.pear.player.ui

import androidx.compose.ui.input.pointer.PointerIcon
import com.mediahub.pear.AndroidHost

/**
 * The system's blank pointer, so a mouse cursor hides over the player along with the controls, as it does on desktop. A
 * touch screen has no cursor to hide, but the blank icon is harmless there. Falls back to the default arrow.
 */
internal actual fun hiddenPointerIcon(): PointerIcon = runCatching {
    PointerIcon(android.view.PointerIcon.getSystemIcon(AndroidHost.app(), android.view.PointerIcon.TYPE_NULL))
}.getOrDefault(PointerIcon.Default)

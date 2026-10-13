package com.mediahub.pear.shell

import androidx.compose.ui.input.pointer.PointerEvent
import java.awt.event.MouseEvent

/** AWT's number for the mouse back button (the fourth button). */
private const val BACK_BUTTON = 4

/**
 * Desktop: the back button going down. AWT reports each press as its own MOUSE_PRESSED event with the button number, so a
 * press is one event and a hold is none. A pointer event with no mouse behind it (touch, for example) is not a back press.
 */
internal actual fun isBackButtonPressed(event: PointerEvent): Boolean {
    val mouse = event.nativeEvent as? MouseEvent ?: return false
    return mouse.id == MouseEvent.MOUSE_PRESSED && mouse.button == BACK_BUTTON
}

package com.mediahub.pear.shell

import androidx.compose.ui.input.pointer.PointerEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/**
 * Desktop: the mouse back button is AWT's button 4. Its state comes from the event's modifier flags, which hold the state
 * of every button after the event, so a move with the button held still reports it. A pointer event with no mouse behind
 * it (touch, for example) is not a back press.
 */
internal actual fun isBackButtonDown(event: PointerEvent): Boolean {
    val mouse = event.nativeEvent as? MouseEvent ?: return false
    return (mouse.modifiersEx and InputEvent.BUTTON4_DOWN_MASK) != 0
}

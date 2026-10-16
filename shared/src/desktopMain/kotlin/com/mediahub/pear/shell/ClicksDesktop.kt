package com.mediahub.pear.shell

import androidx.compose.ui.input.pointer.PointerEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Desktop: the AWT mouse event behind the pointer event says which button it was. Right click is the secondary button
 * (`SwingUtilities.isRightMouseButton`). A pointer event with no mouse behind it (touch, for example) is not a right click.
 */
internal actual fun isSecondaryButtonDown(event: PointerEvent): Boolean {
    val mouse = event.nativeEvent as? MouseEvent ?: return false
    return SwingUtilities.isRightMouseButton(mouse)
}

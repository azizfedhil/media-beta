package com.mediahub.pear.shell

import android.view.MotionEvent
import androidx.compose.ui.input.pointer.PointerEvent

/** Android: the secondary button of a mouse or trackpad. Touch has none, so a finger is never a right click. */
internal actual fun isSecondaryButtonDown(event: PointerEvent): Boolean {
    val motion = event.nativeEvent as? MotionEvent ?: return false
    return (motion.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
}

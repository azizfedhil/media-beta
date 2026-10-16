package com.mediahub.pear.player.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

internal actual fun hiddenPointerIcon(): PointerIcon = try {
    val blank = Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "pear-hidden",
    )
    PointerIcon(blank)
} catch (_: Exception) {
    // Headless toolkit or a platform without custom cursors.
    PointerIcon.Default
}

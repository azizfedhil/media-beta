package com.mediahub.pear

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Desktop
import java.net.URI

actual val platformName: String = "Desktop (${System.getProperty("os.name")})"

actual fun currentTimeLabel(): String =
    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = com.mediahub.pear.net.decodeImageBitmap(bytes, maxEdge)

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    } catch (_: Exception) {}
}
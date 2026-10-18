package com.mediahub.pear.net

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * Decodes [bytes]. A picture whose longest edge is over [maxEdge] is drawn into a surface of that size, so only the
 * scaled copy is kept. A picture that already fits is converted as it is.
 */
actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
    val source = Image.makeFromEncoded(bytes)
    try {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) {
            source.toComposeImageBitmap()
        } else {
            val scale = maxEdge.toFloat() / longest
            val w = maxOf(1, (source.width * scale).roundToInt())
            val h = maxOf(1, (source.height * scale).roundToInt())
            val surface = Surface.makeRasterN32Premul(w, h)
            try {
                surface.canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR)
                val scaled = surface.makeImageSnapshot()
                try {
                    scaled.toComposeImageBitmap()
                } finally {
                    scaled.close()
                }
            } finally {
                surface.close()
            }
        }
    } finally {
        source.close()
    }
} catch (_: Exception) {
    null
}

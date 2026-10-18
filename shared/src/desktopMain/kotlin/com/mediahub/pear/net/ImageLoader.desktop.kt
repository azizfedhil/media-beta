package com.mediahub.pear.net

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * Decodes [bytes]. A picture whose longest edge is over [maxEdge] is drawn, scaled down, into a surface of that size, so
 * only the scaled copy is kept. A picture that already fits is converted as it is.
 *
 * Lifetime: an [ImageBitmap] made by toComposeImageBitmap() draws its Skia image's pixels, so that image stays open. Only
 * the full-size decode is closed here, and only after it has been drawn into the scaled surface.
 */
actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
    val source = Image.makeFromEncoded(bytes)
    val longest = maxOf(source.width, source.height)
    if (longest <= maxEdge) {
        source.toComposeImageBitmap()
    } else {
        val scale = maxEdge.toFloat() / longest
        val w = maxOf(1, (source.width * scale).roundToInt())
        val h = maxOf(1, (source.height * scale).roundToInt())
        try {
            drawScaled(source, w, h)
        } finally {
            source.close()
        }
    }
} catch (_: Exception) {
    null
}

/**
 * Draws [source] into a [w] x [h] surface and returns its snapshot as an [ImageBitmap]. The snapshot keeps its own pixels,
 * so the surface is closed here. The snapshot is not closed, because the returned bitmap draws it.
 */
private fun drawScaled(source: Image, w: Int, h: Int): ImageBitmap {
    val surface = Surface.makeRasterN32Premul(w, h)
    try {
        surface.canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()), // src: the whole picture
            Rect.makeWH(w.toFloat(), h.toFloat()),                         // dst: the scaled size
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),            // mipmapped, so a large downscale does not alias
            null,                                                          // paint
            true,                                                          // strict
        )
        return surface.makeImageSnapshot().toComposeImageBitmap()
    } finally {
        surface.close()
    }
}

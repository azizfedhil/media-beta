package com.mediahub.pear.net

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android decodes at the picture's own size for now: [maxEdge] is not applied here yet. The desktop decoder scales it.
 */
@Suppress("UNUSED_PARAMETER")
actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) {
    null
}

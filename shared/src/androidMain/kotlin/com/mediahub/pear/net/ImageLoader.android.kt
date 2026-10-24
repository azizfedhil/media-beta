package com.mediahub.pear.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier

/**
 * Decodes [bytes]. A picture whose longest edge is over [maxEdge] is read at a power-of-two reduction, the largest one
 * that still leaves it at least [maxEdge] long, so a full-size poster is never held in memory at its original size.
 */
actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = try {
    val options = BitmapFactory.Options()
    if (maxEdge != Int.MAX_VALUE) {
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val longest = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        // A JPEG has no alpha, so on a Low phone it is held at two bytes a pixel. PNGs stay ARGB_8888, because logos need alpha.
        if (DeviceProfile.tier == DeviceTier.Low && options.outMimeType == "image/jpeg") {
            options.inPreferredConfig = Bitmap.Config.RGB_565
        }
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
} catch (_: Exception) {
    null
}

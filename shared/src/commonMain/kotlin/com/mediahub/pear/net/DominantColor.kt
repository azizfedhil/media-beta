package com.mediahub.pear.net

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val dominantCache = HashMap<String, Color>()

/** Main colour of an image: a 24x24 sample weighted towards saturated pixels, then pushed to a deep, moody tone. */
fun ImageBitmap.dominantColor(): Color? {
    val w = width
    val h = height
    if (w < 2 || h < 2) return null
    val row = IntArray(w)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var sum = 0.0
    val n = 24
    for (yi in 0 until n) {
        val y = ((yi + 0.5) * h / n).toInt().coerceIn(0, h - 1)
        readPixels(row, 0, y, w, 1)
        for (xi in 0 until n) {
            val p = row[((xi + 0.5) * w / n).toInt().coerceIn(0, w - 1)]
            val pr = ((p shr 16) and 0xFF) / 255f
            val pg = ((p shr 8) and 0xFF) / 255f
            val pb = (p and 0xFF) / 255f
            val mx = max(pr, max(pg, pb))
            val mn = min(pr, min(pg, pb))
            if (mx < 0.12f) continue // skip near-black
            val sat = (mx - mn) / mx
            val wt = (sat * sat + 0.05f).toDouble()
            r += pr * wt; g += pg * wt; b += pb * wt; sum += wt
        }
    }
    if (sum <= 0.0) return null
    val ar = (r / sum).toFloat()
    val ag = (g / sum).toFloat()
    val ab = (b / sum).toFloat()
    val mx = max(ar, max(ag, ab))
    val mn = min(ar, min(ag, ab))
    val d = mx - mn
    val hue = when {
        d == 0f -> 0f
        mx == ar -> 60f * (((ag - ab) / d + 6f) % 6f)
        mx == ag -> 60f * ((ab - ar) / d + 2f)
        else -> 60f * ((ar - ag) / d + 4f)
    }
    val sat = if (mx == 0f) 0f else d / mx
    return Color.hsv(hue, (sat * 1.1f).coerceIn(0.45f, 0.9f), 0.58f)
}

/** The image's main colour once [RemoteImage] has loaded and cached it; null until then. */
@Composable
fun rememberDominantColor(url: String?): Color? {
    var color by remember(url) { mutableStateOf(url?.let { dominantCache[it] }) }
    LaunchedEffect(url) {
        if (url == null || color != null) return@LaunchedEffect
        repeat(80) {
            val bmp = ImageMemoryCache.get(url)
            if (bmp != null) {
                val c = withContext(Dispatchers.Default) { bmp.dominantColor() }
                if (c != null) {
                    dominantCache[url] = c
                    color = c
                }
                return@LaunchedEffect
            }
            delay(150)
        }
    }
    return color
}

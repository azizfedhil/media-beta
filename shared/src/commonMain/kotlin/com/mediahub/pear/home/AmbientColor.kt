package com.mediahub.pear.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.ImageMemoryCache
import com.mediahub.pear.net.loadRemoteBitmap
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val AMBIENT_BAND_DP = 1100f
private const val AMBIENT_FADE_MILLIS = 900
private val AMBIENT_EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

object AmbientTuning {
    const val WIDE_REACH_DP = 900f
    const val WIDE_REACH_ALPHA = 0.35f
}

object AmbientTone {
    const val MIN_BRIGHTNESS = 0.35f
    const val MAX_BRIGHTNESS = 0.62f

    fun tune(average: Color): Color {
        val hsb = Accents.toHsb(average)
        val saturation = minOf(1f, hsb[1] * 1.3f + 0.08f)
        val brightness = hsb[2].coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        return Accents.fromHsb(hsb[0], saturation, brightness)
    }
}

fun ImageBitmap.averageColor(): Color? {
    val w = width
    val h = height
    if (w < 2 || h < 2) return null
    val row = IntArray(w)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    val n = 24
    for (yi in 0 until n) {
        val y = ((yi + 0.5) * h / n).toInt().coerceIn(0, h - 1)
        readPixels(row, 0, y, w, 1)
        for (xi in 0 until n) {
            val p = row[((xi + 0.5) * w / n).toInt().coerceIn(0, w - 1)]
            r += ((p shr 16) and 0xFF) / 255.0
            g += ((p shr 8) and 0xFF) / 255.0
            b += (p and 0xFF) / 255.0
        }
    }
    val count = (n * n).toDouble()
    return Color((r / count).toFloat(), (g / count).toFloat(), (b / count).toFloat())
}

private val ambientCache = HashMap<String, Color>()

suspend fun heroAmbientFor(http: Http, url: String): Color? {
    ambientCache[url]?.let { return it }
    val bitmap = ImageMemoryCache.get(url) ?: loadRemoteBitmap(http, url) ?: return null
    val tuned = withContext(Dispatchers.Default) { bitmap.averageColor()?.let(AmbientTone::tune) } ?: return null
    ambientCache[url] = tuned
    return tuned
}

@Composable
fun Modifier.attachedAmbient(reachDp: Float = 0f): Modifier {
    val shell = LocalAppEnv.current.shell
    val target = if (shell.heroAmbientKnown) shell.heroAmbientColor else PearTheme.colors.accent
    val glow by animateColorAsState(target, tween(AMBIENT_FADE_MILLIS, easing = AMBIENT_EASE))
    return drawBehind {
        val band = min(size.height, AMBIENT_BAND_DP.dp.toPx())
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(glow.copy(alpha = 0.70f), glow.copy(alpha = 0.25f), Color.Transparent),
                startY = 0f,
                endY = band,
            ),
            size = Size(size.width, band),
        )
        if (reachDp > 0f) {
            val reachPx = reachDp.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(glow.copy(alpha = AmbientTuning.WIDE_REACH_ALPHA), Color.Transparent),
                    startX = 0f,
                    endX = reachPx,
                ),
                size = Size(reachPx, band),
            )
        }
    }
}
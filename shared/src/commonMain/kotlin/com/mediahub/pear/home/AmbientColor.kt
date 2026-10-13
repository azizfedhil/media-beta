package com.mediahub.pear.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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

/** The Home glow's height, in dp: `HomeView.swift` draws `ambient` as a 1100 pt band. */
private const val AMBIENT_BAND_DP = 1100f

/** The colour's fade when a new hero comes in: iOS uses `easeInOut(duration: 0.9)`. */
private const val AMBIENT_FADE_MILLIS = 900

private val AMBIENT_EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * iOS's tuning of a hero's colour (`ImagePipeline.averageColor`): the hue is kept, the saturation is raised a little, and
 * the brightness is held between 0.35 and 0.62, so the glow is never black and never glares.
 */
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

/** The average colour of an image: a 24 x 24 grid of its pixels, each counted the same, as iOS averages a small thumbnail. */
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

/**
 * The glow colour of the hero image at [url]: the image's average, tuned as iOS tunes it. It is the image the page draws, so
 * the colour matches the picture. Cached per URL; null when the image cannot be read.
 */
suspend fun heroAmbientFor(http: Http, url: String): Color? {
    ambientCache[url]?.let { return it }
    val bitmap = ImageMemoryCache.get(url) ?: loadRemoteBitmap(http, url) ?: return null
    val tuned = withContext(Dispatchers.Default) { bitmap.averageColor()?.let(AmbientTone::tune) } ?: return null
    ambientCache[url] = tuned
    return tuned
}

/**
 * The Home glow, drawn as the background of the content that scrolls (iOS: `.background(alignment: .top) { ambient }` on
 * the rows): the glow colour at 70% from the top, 25% halfway, clear at the bottom of a 1100 dp band. Put it on the column
 * that holds the hero and the rows, so the glow moves with them. Until a hero's colour is known the accent is used.
 */
@Composable
fun Modifier.attachedAmbient(): Modifier {
    val shell = LocalAppEnv.current.shell
    val target = if (shell.heroAmbientKnown) shell.heroAmbientColor else PearTheme.colors.accent
    val glow by animateColorAsState(target, tween(AMBIENT_FADE_MILLIS, easing = AMBIENT_EASE))
    return drawBehind {
        val band = min(size.height, AMBIENT_BAND_DP.dp.toPx())
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(glow.copy(alpha = 0.7f), glow.copy(alpha = 0.25f), Color.Transparent),
                startY = 0f,
                endY = band,
            ),
            size = Size(size.width, band),
        )
    }
}

package com.mediahub.pear.net

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes raw image bytes into Compose [ImageBitmap] via platform image decoders. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/** Simple memory cache for decoded image bitmaps. */
object ImageMemoryCache {
    private val cache = LinkedHashMap<String, ImageBitmap>(100, 0.75f, true)
    private const val MAX_ENTRIES = 250

    @Synchronized
    fun get(url: String): ImageBitmap? = cache[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        if (cache.size >= MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        cache[url] = bitmap
    }
}

/**
 * Loads an image from [url] with caching, shimmer placeholder, and fallback icon.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallbackUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val http = LocalHttp.current
    var bitmap by remember(url) { mutableStateOf(url?.let { ImageMemoryCache.get(it) }) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect

        failed = false
        val targetUrl = url
        val loaded: ImageBitmap? = withContext(Dispatchers.Default) {
            val targetReq = HttpRequest(targetUrl, timeoutMillis = 10_000)
            var bytes: ByteArray? = attempt { http.execute(targetReq).body }
            if (bytes == null && fallbackUrl != null) {
                val fallbackReq = HttpRequest(fallbackUrl, timeoutMillis = 10_000)
                bytes = attempt { http.execute(fallbackReq).body }
            }
            bytes?.let { decodeImageBitmap(it) }
        }

        if (loaded != null) {
            ImageMemoryCache.put(targetUrl, loaded)
            bitmap = loaded
        } else {
            failed = true
        }
    }

    Box(modifier) {
        val currentBitmap = bitmap
        when {
            currentBitmap != null -> {
                Image(
                    bitmap = currentBitmap,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            failed -> {
                Box(Modifier.fillMaxSize().background(PearTheme.colors.fill), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PearIcon("film", size = 24.dp, tint = PearTheme.colors.textTertiary)
                }
            }
            else -> {
                Shimmer(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = -1000f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            PearTheme.colors.fillSubtle,
            PearTheme.colors.fillStrong,
            PearTheme.colors.fillSubtle,
        ),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 400f, 400f),
    )

    Box(modifier.background(brush))
}
package com.mediahub.pear.net

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.pearShimmer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes raw image bytes into Compose [ImageBitmap] via platform image decoders. A picture whose longest edge is over
 * [maxEdge] is scaled down to it, so a full-size poster is kept (and uploaded to the GPU) at the size it is drawn at.
 */
expect fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int = Int.MAX_VALUE): ImageBitmap?

/** The longest edge, in pixels, a picture is decoded to unless a caller asks for another size. */
const val DEFAULT_MAX_EDGE = 1600

/**
 * Where the decode in [loadRemoteBitmap] runs. The UI thread never decodes. Tests set this to their own dispatcher, since
 * the test scheduler cannot see work moved to a real one.
 */
object ImageDecode {
    var dispatcher: CoroutineDispatcher = Dispatchers.Default
}

/**
 * Memory cache for decoded image bitmaps, least recently used first out. It is capped by count and by decoded size:
 * a full-resolution poster is about 24 MB as pixels, so a count alone would let a few dozen of them use gigabytes.
 */
object ImageMemoryCache {
    private val cache = LinkedHashMap<String, ImageBitmap>(100, 0.75f, true)
    private var bytes = 0L
    private const val MAX_ENTRIES = 250

    /** Decoded pixels kept at most (4 bytes a pixel). */
    private const val MAX_BYTES = 256L * 1024 * 1024

    /** One size of one picture is one entry: the same URL at two sizes is two entries. */
    private fun keyOf(url: String, maxEdge: Int): String = "$url@$maxEdge"

    @Synchronized
    fun get(url: String, maxEdge: Int = DEFAULT_MAX_EDGE): ImageBitmap? = cache[keyOf(url, maxEdge)]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap, maxEdge: Int = DEFAULT_MAX_EDGE) {
        val key = keyOf(url, maxEdge)
        cache.remove(key)?.let { bytes -= sizeOf(it) }
        cache[key] = bitmap
        bytes += sizeOf(bitmap)
        while (cache.size > 1 && (cache.size > MAX_ENTRIES || bytes > MAX_BYTES)) {
            val oldest = cache.keys.first()
            val removed = cache.remove(oldest) ?: break
            bytes -= sizeOf(removed)
        }
    }

    private fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4L

    /** Forget everything. Tests use it so one test's images can't answer for another's (a cached hit skips the request). */
    @Synchronized
    fun clear() {
        cache.clear()
        bytes = 0L
    }
}

/**
 * Loads an image from [url] with caching, shimmer placeholder, and fallback icon. [maxEdge] is the longest edge the
 * picture is decoded to: pass the size it is drawn at, so a small thumb is not decoded at its original size.
 * [shimmer] draws the moving placeholder instead of the flat one; [fadeInMillis] fades a picture in when it was not
 * already in memory (0 = no fade).
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallbackUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdge: Int = DEFAULT_MAX_EDGE,
    shimmer: Boolean = false,
    fadeInMillis: Int = 0,
) {
    val http = LocalHttp.current
    var bitmap by remember(url, maxEdge) { mutableStateOf(url?.let { ImageMemoryCache.get(it, maxEdge) }) }
    var failed by remember(url, maxEdge) { mutableStateOf(false) }
    // A picture that was already in memory shows at once; one that had to be fetched fades in.
    val reveal = remember(url, maxEdge) { Animatable(if (bitmap != null || fadeInMillis <= 0) 1f else 0f) }
    LaunchedEffect(bitmap) {
        if (bitmap != null && reveal.value < 1f) reveal.animateTo(1f, tween(fadeInMillis))
    }

    LaunchedEffect(url, maxEdge) {
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
            bytes?.let { decodeImageBitmap(it, maxEdge) }
        }

        if (loaded != null) {
            ImageMemoryCache.put(targetUrl, loaded, maxEdge)
            bitmap = loaded
        } else {
            failed = true
        }
    }

    Box(modifier) {
        val currentBitmap = bitmap
        if (currentBitmap == null || reveal.value < 1f) {
            // The placeholder stays underneath until the fade-in has finished.
            when {
                failed -> Box(Modifier.fillMaxSize().background(PearTheme.colors.fill), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PearIcon("film", size = 24.dp, tint = PearTheme.colors.textTertiary)
                }
                shimmer -> Box(Modifier.fillMaxSize().pearShimmer())
                else -> ImagePlaceholder(Modifier.fillMaxSize())
            }
        }
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
            )
        }
    }
}

/** The placeholder while an image loads: a flat surface fill, as on iOS (a flat secondary fill, no animation). */
@Composable
fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(PearTheme.colors.surface))
}

/**
 * Loads and decodes [url] through the same memory cache [RemoteImage] uses, at the same [maxEdge]. Null when the image
 * cannot be loaded. Callers that must know whether an image arrived (a title logo replaces its text only once it has
 * loaded) use this, not RemoteImage, which shows a placeholder or an icon instead.
 */
suspend fun loadRemoteBitmap(http: Http, url: String, maxEdge: Int = DEFAULT_MAX_EDGE): ImageBitmap? {
    ImageMemoryCache.get(url, maxEdge)?.let { return it }
    // The network call stays on the caller's context: callers that must know whether an image arrived wait on it. The
    // decode runs on ImageDecode.dispatcher, so the UI thread never decodes. Tests drive this call through a virtual-time
    // scheduler that cannot see work moved to a real dispatcher, so they set ImageDecode.dispatcher to their own.
    val reply = attempt { http.execute(HttpRequest(url, timeoutMillis = 10_000)) } ?: return null
    // An error answer is never a picture. It isn't decoded at all, so a 404 costs no image work.
    if (reply.status !in 200..299) return null
    val loaded = withContext(ImageDecode.dispatcher) { decodeImageBitmap(reply.body, maxEdge) } ?: return null
    ImageMemoryCache.put(url, loaded, maxEdge)
    return loaded
}

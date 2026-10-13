package com.mediahub.pear.net

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes raw image bytes into Compose [ImageBitmap] via platform image decoders. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

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

    @Synchronized
    fun get(url: String): ImageBitmap? = cache[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        cache.remove(url)?.let { bytes -= sizeOf(it) }
        cache[url] = bitmap
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
                ImagePlaceholder(Modifier.fillMaxSize())
            }
        }
    }
}

/** The placeholder while an image loads: a flat surface fill, as on iOS (a flat secondary fill, no animation). */
@Composable
fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(PearTheme.colors.surface))
}

/**
 * Loads and decodes [url] through the same memory cache [RemoteImage] uses. Null when the image cannot be loaded. Callers
 * that must know whether an image arrived (a title logo replaces its text only once it has loaded) use this, not RemoteImage,
 * which shows a placeholder or an icon instead.
 */
suspend fun loadRemoteBitmap(http: Http, url: String): ImageBitmap? {
    ImageMemoryCache.get(url)?.let { return it }
    // Runs on the caller's context, not Dispatchers.Default: callers that must know whether an image arrived wait on
    // this call, and tests drive it through a virtual-time scheduler that cannot see work moved to a real dispatcher.
    val reply = attempt { http.execute(HttpRequest(url, timeoutMillis = 10_000)) } ?: return null
    // An error answer is never a picture. It isn't decoded at all, so a 404 costs no image work.
    if (reply.status !in 200..299) return null
    val loaded = decodeImageBitmap(reply.body) ?: return null
    ImageMemoryCache.put(url, loaded)
    return loaded
}

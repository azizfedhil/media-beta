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
import com.mediahub.pear.epochMillis
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.LocalImageCache
import com.mediahub.pear.shell.PlatformCaps
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.pearShimmer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** Decodes at once on a touch device: 2 on a Low phone, where each one holds more of a small memory budget. A guess. */
    private const val LOW_DECODE_PARALLELISM = 2

    /** Decodes at once on a touch device that is not Low. A guess. */
    private const val DECODE_PARALLELISM = 3

    /** Desktop keeps Dispatchers.Default. A touch device gets its own pool, made on first use, after the tier is set. */
    private val defaultDispatcher: CoroutineDispatcher by lazy {
        if (!PlatformCaps.isTouchFirst) {
            Dispatchers.Default
        } else {
            val parallelism = if (DeviceProfile.tier == DeviceTier.Low) LOW_DECODE_PARALLELISM else DECODE_PARALLELISM
            Dispatchers.Default.limitedParallelism(parallelism)
        }
    }

    private var assigned: CoroutineDispatcher? = null

    /** Where decodes run. A test assigns its own; without one, reading this gives the default. */
    var dispatcher: CoroutineDispatcher
        get() = assigned ?: defaultDispatcher
        set(value) {
            assigned = value
        }
}

/**
 * Memory cache for decoded image bitmaps, least recently used first out. It is capped by count and by decoded size:
 * a full-resolution poster is about 24 MB as pixels, so a count alone would let a few dozen of them use gigabytes.
 */
object ImageMemoryCache {
    private val cache = LinkedHashMap<String, ImageBitmap>(100, 0.75f, true)
    private var bytes = 0L

    /** Entries kept at most. PearApplication lowers this on smaller phones. */
    @Volatile private var maxEntries = 250

    /** Decoded pixels kept at most (4 bytes a pixel). PearApplication lowers this on smaller phones. */
    @Volatile private var maxBytes = 256L * 1024 * 1024

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
        while (cache.size > 1 && (cache.size > maxEntries || bytes > maxBytes)) {
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

    /** Sets the limits for this device. The next [put] enforces them, so entries already cached can stay above them until then. */
    @Synchronized
    fun configure(maxBytes: Long, maxEntries: Int) {
        this.maxBytes = maxBytes
        this.maxEntries = maxEntries
    }

    /** Evicts least recently used entries until the decoded size is at most [maxBytes]. Used when memory runs low. */
    @Synchronized
    fun trimTo(maxBytes: Long) {
        while (cache.isNotEmpty() && bytes > maxBytes) {
            val oldest = cache.keys.first()
            val removed = cache.remove(oldest) ?: break
            bytes -= sizeOf(removed)
        }
    }
}

/** Longest edge the low-quality stand-in is decoded to. */
const val PREVIEW_MAX_EDGE = 160

/** The full picture gets this long alone first. A disk hit or a fast network never pays for a second request. */
private const val PREVIEW_DELAY_MILLIS = 150L

/** How long the full picture takes to fade in over a stand-in. */
private const val PREVIEW_CROSSFADE_MILLIS = 200

/**
 * Picture for [url]: the disk cache first, then the network. A picture that decoded is written to [disk]; an error
 * answer or something that does not decode is never stored. A damaged cached file is fetched again. Null when there is
 * no picture. The decode runs on [decodeOn], never on the UI thread.
 */
internal suspend fun loadImageBitmap(
    http: Http,
    disk: ResponseCache,
    url: String,
    maxEdge: Int,
    decodeOn: CoroutineDispatcher = ImageDecode.dispatcher,
): ImageBitmap? {
    attempt { disk.get(url) }?.let { hit ->
        val cached = withContext(decodeOn) { decodeImageBitmap(hit.body, maxEdge) }
        if (cached != null) return cached
    }
    val reply = attempt { http.execute(HttpRequest(url, timeoutMillis = 10_000)) } ?: return null
    if (reply.status !in 200..299) return null
    val loaded = withContext(decodeOn) { decodeImageBitmap(reply.body, maxEdge) } ?: return null
    attempt { disk.put(url, reply.body, epochMillis()) }
    return loaded
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
    val disk = LocalImageCache.current
    val edge = DeviceProfile.capEdge(maxEdge)
    var bitmap by remember(url, edge) { mutableStateOf(url?.let { ImageMemoryCache.get(it, edge) }) }
    // A small, blurry copy shown until the full picture is ready. It is never set once the full one is there.
    var preview by remember(url, edge) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url, edge) { mutableStateOf(false) }
    // A picture already in memory shows at once; one that had to be loaded fades in over whatever is underneath.
    val reveal = remember(url, edge) { Animatable(if (bitmap != null) 1f else 0f) }
    LaunchedEffect(bitmap) {
        if (bitmap == null || reveal.value >= 1f) return@LaunchedEffect
        val ms = if (fadeInMillis > 0) fadeInMillis else if (preview != null) PREVIEW_CROSSFADE_MILLIS else 0
        if (ms > 0) reveal.animateTo(1f, tween(ms)) else reveal.snapTo(1f)
    }

    LaunchedEffect(url, edge) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect

        failed = false
        val lowUrl = lowQualityUrl(url)
        preview = lowUrl?.let { ImageMemoryCache.get(it, PREVIEW_MAX_EDGE) }
        coroutineScope {
            val full = async(Dispatchers.Default) {
                var loaded = loadImageBitmap(http, disk, url, edge)
                if (loaded == null && fallbackUrl != null) loaded = loadImageBitmap(http, disk, fallbackUrl, edge)
                loaded
            }
            // Only asked for when the full picture is slow: a disk hit or a quick answer cancels this before it starts.
            val previewJob = if (lowUrl != null && preview == null) {
                launch(Dispatchers.Default) {
                    delay(PREVIEW_DELAY_MILLIS)
                    val small = loadImageBitmap(http, disk, lowUrl, PREVIEW_MAX_EDGE) ?: return@launch
                    ImageMemoryCache.put(lowUrl, small, PREVIEW_MAX_EDGE)
                    if (bitmap == null) preview = small
                }
            } else null

            val loaded = full.await()
            if (loaded != null) previewJob?.cancel() else previewJob?.join()
            if (loaded != null) {
                ImageMemoryCache.put(url, loaded, edge)
                bitmap = loaded
            } else if (preview == null) {
                failed = true
            }
        }
    }

    Box(modifier) {
        val currentBitmap = bitmap
        val stand = preview
        if (currentBitmap == null || reveal.value < 1f) {
            // Underneath until the fade-in has finished: the small copy when there is one, else the flat or shimmer fill.
            when {
                stand != null -> Image(
                    bitmap = stand,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
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
suspend fun loadRemoteBitmap(
    http: Http,
    url: String,
    maxEdge: Int = DEFAULT_MAX_EDGE,
    disk: ResponseCache = ResponseCache.None,
): ImageBitmap? {
    val edge = DeviceProfile.capEdge(maxEdge)
    ImageMemoryCache.get(url, edge)?.let { return it }
    // The network call stays on the caller's context: callers that must know whether an image arrived wait on it. The
    // decode runs on ImageDecode.dispatcher, so the UI thread never decodes. Tests drive this call through a virtual-time
    // scheduler that cannot see work moved to a real dispatcher, so they set ImageDecode.dispatcher to their own.
    val loaded = loadImageBitmap(http, disk, url, edge) ?: return null
    ImageMemoryCache.put(url, loaded, edge)
    return loaded
}

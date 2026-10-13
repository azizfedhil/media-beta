package com.mediahub.pear.addons

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.model.jStr
import com.mediahub.pear.model.jStrings
import com.mediahub.pear.net.BadStatusException
import com.mediahub.pear.net.BadUrlException
import com.mediahub.pear.net.CachedBody
import com.mediahub.pear.net.CachedFetcher
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.NetworkException
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.net.Ttl
import com.mediahub.pear.net.attempt
import com.mediahub.pear.servers.MediaServerRegistry
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/** The two things Home's theme rows take from an add-on's `meta` response. */
data class MetaExtras(val genres: List<String>, val logo: String?) {
    companion object {
        val Empty = MetaExtras(emptyList(), null)
    }
}

private const val MANIFEST_TTL = 6 * Ttl.HOUR
private const val CATALOG_TTL = 30 * Ttl.MINUTE
private const val REQUEST_TIMEOUT_MILLIS = 12_000L
private const val HEX = "0123456789ABCDEF"

/**
 * Talks the Stremio add-on protocol: manifests, catalogues, `meta` extras and stream lookups (spec: `AddonClient`).
 */
class AddonClient(
    private val http: Http,
    cache: ResponseCache = ResponseCache.None,
    private val now: () -> Long = ::epochMillis,
    private val streamFilter: suspend (List<StreamItem>) -> List<StreamItem> = { it },
    private val streamDeadlineMillis: Long = 8_000,
    private val retryDelayMillis: (Int) -> Long = { it * 1_500L },
) {
    private val scoped = ScopedCache(cache, now)
    private val fetcher = CachedFetcher(http, scoped, now)
    private val metaLock = Mutex()
    private val metaCache = HashMap<String, MetaExtras>()

    // MARK: Manifests and catalogues

    suspend fun manifest(url: String, fresh: Boolean = false): AddonManifest {
        var last: Exception = NetworkException("Unknown error")
        for (n in 0 until 3) {
            if (n > 0) delay(retryDelayMillis(n))
            try {
                return get(url, MANIFEST_TTL, readCache = !(fresh || n > 0)) { AddonManifest.parse(it.decodeToString()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    suspend fun clearCache() = scoped.clear()

    suspend fun catalog(addon: Addon, catalog: AddonManifest.CatalogDef, skip: Int = 0, search: String? = null): List<MetaPreview> {
        val server = MediaServerRegistry.backend(addon)
        if (server != null) {
            return server.catalog(catalog.type, catalog.id, skip, search)
        }

        val extras = ArrayList<String>()
        val q = search?.trim()
        if (!q.isNullOrEmpty()) extras.add("search=" + percentEncode(q))
        if (skip > 0) extras.add("skip=$skip")
        var path = "catalog/${catalog.type}/${catalog.id}"
        if (extras.isNotEmpty()) path += "/" + extras.joinToString("&")
        val url = addon.baseUrl + path + ".json"
        if (attempt { Url(url) } == null) throw BadUrlException()
        return get(url, CATALOG_TTL, readCache = true) { MetaPreview.decodeMetas(it.decodeToString()) }
    }

    private suspend fun <T> get(url: String, ttl: Long, readCache: Boolean, parse: (ByteArray) -> T): T =
        fetcher.fetch(
            key = "addon:" + hashKey(url),
            ttlMillis = ttl,
            build = { HttpRequest(url, headers = mapOf("Accept" to "application/json"), timeoutMillis = REQUEST_TIMEOUT_MILLIS) },
            parse = parse,
            readCache = readCache,
        )

    // MARK: Metadata extras (theme rows)

    suspend fun metaExtras(addon: Addon, type: String, id: String): MetaExtras {
        val key = addon.id + "|" + type + "|" + id
        metaLock.withLock { metaCache[key] }?.let { return it }
        if (!addon.provides("meta", type, id)) {
            metaLock.withLock { metaCache[key] = MetaExtras.Empty }
            return MetaExtras.Empty
        }
        val url = addon.baseUrl + "meta/$type/${encodeId(id)}.json"
        val data = lookup(url) ?: return MetaExtras.Empty
        val out = attempt { parseMetaExtras(data.decodeToString()) } ?: MetaExtras.Empty
        metaLock.withLock { metaCache[key] = out }
        return out
    }

    // MARK: Streams

    fun streamUpdates(id: String, type: String, addons: List<Addon>): Flow<Pair<Addon, List<StreamItem>>> = channelFlow {
        for (addon in addons) {
            if (!addon.provides("stream", type, id)) continue
            launch {
                val found = streamsOf(addon, type, id)
                if (found != null) send(addon to found)
            }
        }
    }

    suspend fun streams(id: String, type: String, addons: List<Addon>): List<Pair<Addon, List<StreamItem>>> {
        val out = ArrayList<Pair<Addon, List<StreamItem>>>()
        streamUpdates(id, type, addons).collect { out.add(it) }
        return out
    }

    private suspend fun streamsOf(addon: Addon, type: String, id: String): List<StreamItem>? {
        val server = MediaServerRegistry.backend(addon)
        if (server != null) {
            val found = server.streams(type, id)
            return if (found.isEmpty()) null else streamFilter(found)
        }
        val url = addon.baseUrl + "stream/$type/${encodeId(id)}.json"
        val data = lookup(url) ?: return null
        val items = attempt { StreamItem.decodeStreams(data.decodeToString()) } ?: return null
        return streamFilter(items)
    }

    private suspend fun lookup(url: String): ByteArray? {
        if (attempt { Url(url) } == null) return null
        return attempt {
            withTimeoutOrNull(streamDeadlineMillis) {
                val reply = http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json"), timeoutMillis = streamDeadlineMillis))
                if (reply.status != 200) throw BadStatusException(reply.status)
                reply.body
            }
        }
    }

    companion object {
        fun ordered(groups: List<Pair<Addon, List<StreamItem>>>, by: List<Addon>): List<Pair<Addon, List<StreamItem>>> =
            by.mapNotNull { a -> groups.firstOrNull { it.first.id == a.id } }

        internal fun parseMetaExtras(text: String): MetaExtras {
            val root = PearJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("Expected an object")
            val meta = root["meta"] as? JsonObject ?: throw SerializationException("No meta")
            return MetaExtras(genres = meta["genres"].jStrings() ?: emptyList(), logo = meta["logo"].jStr())
        }
    }
}

private class ScopedCache(private val inner: ResponseCache, private val now: () -> Long) : ResponseCache {
    @Volatile
    private var clearedAt = Long.MIN_VALUE

    override suspend fun get(key: String): CachedBody? = inner.get(key)?.takeIf { it.storedAtMillis > clearedAt }
    override suspend fun put(key: String, body: ByteArray, nowMillis: Long) = inner.put(key, body, nowMillis)
    override suspend fun clear() {
        clearedAt = now()
    }
    override suspend fun describe(): String = inner.describe()
}

internal fun hashKey(text: String): String {
    var h = -3750763034362895579L
    for (b in text.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xff)
        h *= 1099511628211L
    }
    return h.toULong().toString(16).padStart(16, '0')
}

private fun isUnreserved(c: Char): Boolean =
    (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~'

fun percentEncode(s: String, keep: (Char) -> Boolean = ::isUnreserved): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c < 128 && keep(c.toChar())) {
            sb.append(c.toChar())
        } else {
            sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }
    return sb.toString()
}

internal fun encodeId(id: String): String = percentEncode(id) { isUnreserved(it) || it in ":@!&'()*+,;=" || it == '$' }
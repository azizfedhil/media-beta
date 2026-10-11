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

/** What the add-on protocol needs from a network: a catalogue answers in minutes, a manifest in hours. */
private const val MANIFEST_TTL = 6 * Ttl.HOUR
private const val CATALOG_TTL = 30 * Ttl.MINUTE
private const val REQUEST_TIMEOUT_MILLIS = 12_000L
private const val HEX = "0123456789ABCDEF"

/**
 * Talks the Stremio add-on protocol: manifests, catalogues, `meta` extras and stream lookups (spec: `AddonClient`).
 * One shared instance; safe to call from any thread.
 *
 * - Manifests and catalogues are kept on disk ([MANIFEST_TTL], [CATALOG_TTL]), and an older copy is served when the
 *   add-on can't be reached. Add-on addresses can hold API keys, so the cache key is a hash, never the address.
 * - A manifest fetch retries twice (cold-starting hosts, a network that isn't up yet at launch).
 * - Stream lookups are never cached (a cached empty or failed answer would hide an add-on) and have a hard
 *   [streamDeadlineMillis] per add-on: a trickling response can't hold up the list.
 *
 * Not here on purpose: Jellyfin/Plex servers (not in the port plan) and the P2P gate. A torrent filter is passed in as
 * [streamFilter], which Batch 4.5 provides.
 */
class AddonClient(
    private val http: Http,
    cache: ResponseCache = ResponseCache.None,
    private val now: () -> Long = ::epochMillis,
    /** Runs on every add-on's stream list before it is handed out (Swift: `P2PGate.apply`). */
    private val streamFilter: suspend (List<StreamItem>) -> List<StreamItem> = { it },
    private val streamDeadlineMillis: Long = 8_000,
    /** Wait before manifest retry number `n` (1 or 2). */
    private val retryDelayMillis: (Int) -> Long = { it * 1_500L },
) {
    private val scoped = ScopedCache(cache, now)
    private val fetcher = CachedFetcher(http, scoped, now)
    private val metaLock = Mutex()
    private val metaCache = HashMap<String, MetaExtras>()

    // MARK: Manifests and catalogues

    /**
     * The manifest at [url]. Retries twice on a failed fetch, and the retries ask the add-on itself rather than the
     * cache. [fresh] skips the cache from the first try (adding or reloading an add-on).
     */
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

    /** Hides every answer kept so far, so the next fetch goes to the network. Lasts for this run (see docs). */
    suspend fun clearCache() = scoped.clear()

    /** [skip] pages through add-ons that declare it; [search] queries add-ons that declare it. */
    suspend fun catalog(addon: Addon, catalog: AddonManifest.CatalogDef, skip: Int = 0, search: String? = null): List<MetaPreview> {
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

    /**
     * Genres and logo for one title from [addon]'s `meta` resource ([MetaExtras.Empty] when it has none, doesn't serve
     * this id, or doesn't answer within the deadline). Answers are kept for the run: a title is asked about once however
     * often Home reloads. Failures and timeouts are not kept, so a flaky moment doesn't blank a title until the next launch.
     */
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

    /**
     * Asks only the add-ons that declare the `stream` resource for this type and id, and emits each add-on's result
     * the moment it answers (completion order; use [ordered] to restore the user's add-on order). Add-ons that fail
     * or time out are simply skipped. Cancelling the collector cancels the lookups.
     */
    fun streamUpdates(id: String, type: String, addons: List<Addon>): Flow<Pair<Addon, List<StreamItem>>> = channelFlow {
        for (addon in addons) {
            if (!addon.provides("stream", type, id)) continue
            launch {
                val found = streamsOf(addon, type, id)
                if (found != null) send(addon to found)
            }
        }
    }

    /** The same fan-out, waiting for everyone. */
    suspend fun streams(id: String, type: String, addons: List<Addon>): List<Pair<Addon, List<StreamItem>>> {
        val out = ArrayList<Pair<Addon, List<StreamItem>>>()
        streamUpdates(id, type, addons).collect { out.add(it) }
        return out
    }

    private suspend fun streamsOf(addon: Addon, type: String, id: String): List<StreamItem>? {
        val url = addon.baseUrl + "stream/$type/${encodeId(id)}.json"
        val data = lookup(url) ?: return null
        val items = attempt { StreamItem.decodeStreams(data.decodeToString()) } ?: return null
        return streamFilter(items)
    }

    /** One uncached GET with a real deadline (the request races a timer). null on any failure. */
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
        /** Sorts results into the user's add-on order. */
        fun ordered(groups: List<Pair<Addon, List<StreamItem>>>, by: List<Addon>): List<Pair<Addon, List<StreamItem>>> =
            by.mapNotNull { a -> groups.firstOrNull { it.first.id == a.id } }

        internal fun parseMetaExtras(text: String): MetaExtras {
            val root = PearJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("Expected an object")
            val meta = root["meta"] as? JsonObject ?: throw SerializationException("No meta")
            return MetaExtras(genres = meta["genres"].jStrings() ?: emptyList(), logo = meta["logo"].jStr())
        }
    }
}

/**
 * The shared response cache seen through a "cleared at" time: answers stored before the last [clear] are ignored.
 * This is how the add-on client clears only its own answers without emptying TMDB's.
 */
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

/** FNV-1a, 64 bit, as 16 hex digits: a cache key that doesn't reveal an add-on address. */
internal fun hashKey(text: String): String {
    var h = -3750763034362895579L // 0xcbf29ce484222325
    for (b in text.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xff)
        h *= 1099511628211L
    }
    return h.toULong().toString(16).padStart(16, '0')
}

private fun isUnreserved(c: Char): Boolean =
    (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~'

/** Percent-encodes UTF-8 bytes; characters [keep] says yes to stay as they are. */
internal fun percentEncode(s: String, keep: (Char) -> Boolean = ::isUnreserved): String {
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

/** A title id as one path segment: `tt123:1:2` stays readable, a `/` or a space is escaped. */
internal fun encodeId(id: String): String = percentEncode(id) { isUnreserved(it) || it in ":@!&'()*+,;=" || it == '$' }

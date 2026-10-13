package com.mediahub.pear.meta

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.CachedFetcher
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.NoKeyException
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.net.Ttl
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/** One of the user's own lists on mdblist.com. */
@Serializable
data class MdbUserList(val id: Int, val name: String, val items: Int? = null)

/** A rating from one source, as shown on the detail page. */
data class MdbRating(val label: String, val text: String, val score: Double? = null) {
    val id: String get() = label
}

@Serializable
private class MdbRatingsResponse(val ratings: List<R>? = null) {
    @Serializable
    class R(val source: String, val value: Double? = null)
}

@Serializable
internal data class MdbListItem(
    val title: String,
    @SerialName("imdb_id") val imdbId: String? = null,
    val mediatype: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
)

/**
 * Aggregated ratings and the user's own MDBList lists (spec: `MDBListClient.swift`). Needs an API key (Settings).
 * Requests are on demand only. Ratings are kept on disk (they belong to the title, not the account); lists belong to
 * the account, so they are only kept in memory.
 */
class MdbListClient(
    http: Http,
    private val apiKey: () -> String,
    cache: ResponseCache = ResponseCache.None,
    private val now: () -> Long = ::epochMillis,
) {
    private val fetcher = CachedFetcher(http, cache, now)
    private val lock = Mutex()
    private val ratingCache = HashMap<String, List<MdbRating>>()
    private val listCache = HashMap<Int, Pair<Long, List<MetaPreview>>>()

    val hasKey: Boolean get() = apiKey().trim().isNotEmpty()

    private suspend fun <T> get(path: String, ttl: Long, query: Map<String, String> = emptyMap(), parse: (ByteArray) -> T): T =
        withContext(Dispatchers.Default) {
            val key = apiKey().trim()
            if (key.isEmpty()) throw NoKeyException("MDBList")
            fetcher.fetch(
                key = "mdblist:" + path + if (query.isEmpty()) "" else "?" + query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" },
                ttlMillis = ttl,
                build = { HttpRequest(url = buildUrl("https://api.mdblist.com$path", mapOf("apikey" to key) + query)) },
                parse = parse,
            )
        }

    private fun <T> json(serializer: KSerializer<T>): (ByteArray) -> T = { PearJson.decodeFromString(serializer, it.decodeToString()) }

    /** Ratings of a title, in display order. Empty on any failure (and not remembered then). */
    suspend fun ratings(imdb: String, type: String): List<MdbRating> {
        lock.withLock { ratingCache[imdb] }?.let { return it }
        if (!imdb.startsWith("tt")) return emptyList() // MDBList is keyed by IMDb id; anything else can't match
        val kind = if (type == "series") "show" else "movie"
        val r = attempt { get("/imdb/$kind/${imdb.encodeURLPathPart()}", Ttl.HOUR * 6, parse = json(MdbRatingsResponse.serializer())) }
            ?: return emptyList()
        val by = HashMap<String, Double>()
        for (x in r.ratings ?: emptyList()) {
            val v = x.value ?: continue
            if (x.source !in by) by[x.source] = v // the first of a source wins
        }
        val out = ArrayList<MdbRating>()
        by["imdb"]?.let { out.add(MdbRating("IMDb", oneDecimal(it), it)) }
        by["tomatoes"]?.let { out.add(MdbRating("Rotten Tomatoes", "${it.toInt()}%", it)) }
        (by["tomatoesaudience"] ?: by["popcorn"])?.let { out.add(MdbRating("RT Audience", "${it.toInt()}%", it)) }
        by["metacritic"]?.let { out.add(MdbRating("Metacritic", "${it.toInt()}", it)) }
        by["letterboxd"]?.let { out.add(MdbRating("Letterboxd", oneDecimal(it), it)) }
        by["trakt"]?.let { out.add(MdbRating("Trakt", "${it.toInt()}%", it)) }
        lock.withLock { ratingCache[imdb] = out }
        return out
    }

    /** The user's lists. Empty on any failure. */
    suspend fun userLists(): List<MdbUserList> =
        attempt { get("/lists/user", ttl = 0, parse = json(ListSerializer(MdbUserList.serializer()))) } ?: emptyList()

    /** The first 30 titles of a list (kept 30 minutes). List items carry no artwork: [MetaPreview] falls back to images keyed by IMDb id. */
    suspend fun items(listId: Int): List<MetaPreview> {
        val cached = lock.withLock { listCache[listId] }
        if (cached != null && (now() - cached.first) in 0 until 30 * Ttl.MINUTE) return cached.second
        val r = attempt { get("/lists/$listId/items", ttl = 0, query = mapOf("limit" to "30"), parse = { parseItems(it) }) } ?: return emptyList()
        val out = r.mapNotNull { i ->
            val id = i.imdbId ?: return@mapNotNull null
            MetaPreview(id = id, type = if (i.mediatype == "show") "series" else "movie", name = i.title, releaseInfo = i.releaseYear?.toString())
        }
        lock.withLock { listCache[listId] = now() to out }
        return out
    }

    internal companion object {
        /** `%.1f`: one decimal, rounded half up. */
        fun oneDecimal(v: Double): String {
            val tenths = (v * 10).roundToLong()
            val sign = if (tenths < 0) "-" else ""
            val a = if (tenths < 0) -tenths else tenths
            return "$sign${a / 10}.${a % 10}"
        }

        /**
         * Newer API answers `{movies: [], shows: []}`, older a bare array. In the object form a half that can't be
         * read is just empty; in the array form an unreadable item is an error, as in the Swift decoder.
         */
        fun parseItems(bytes: ByteArray): List<MdbListItem> {
            val root = PearJson.parseToJsonElement(bytes.decodeToString())
            if (root is JsonObject) {
                fun load(key: String, kind: String): List<MdbListItem> {
                    val arr = root[key] as? JsonArray ?: return emptyList()
                    val list = attempt { PearJson.decodeFromJsonElement(ListSerializer(MdbListItem.serializer()), arr) } ?: return emptyList()
                    return list.map { if (it.mediatype == null) it.copy(mediatype = kind) else it }
                }
                return load("movies", "movie") + load("shows", "show")
            }
            if (root is JsonArray) return PearJson.decodeFromJsonElement(ListSerializer(MdbListItem.serializer()), root)
            throw SerializationException("Unexpected list answer")
        }
    }
}

package com.mediahub.pear.meta

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.data.SecretStore
import com.mediahub.pear.epochMillis
import com.mediahub.pear.net.BadStatusException
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One TVDB episode (spec: `TVDBClient.Episode`). */
@Serializable
data class TvdbEpisode(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    val image: String? = null,
    val number: Int? = null,
    val seasonNumber: Int? = null,
    val runtime: Int? = null,
    /** "2024-05-01". Lets the episode list tell what hasn't aired yet when TVDB stands in for TMDB. */
    val aired: String? = null,
) {
    val imageUrl: String? get() = image?.let { TvdbClient.absolute(it) }
}

@Serializable
private class TvdbEnvelope<T>(val data: T)

@Serializable
private class TvdbLogin(val token: String)

@Serializable
private class TvdbEpisodePage(val episodes: List<TvdbEpisode>)

@Serializable
private class TvdbRemote(val series: TvdbRef? = null, val movie: TvdbRef? = null)

@Serializable
private class TvdbArt(val image: String, val type: Int, val score: Int? = null, val language: String? = null)

@Serializable
private class TvdbArts(val artworks: List<TvdbArt>? = null)

/** TheTVDB sometimes sends ids as numbers, sometimes as strings. */
@Serializable(with = TvdbRefSerializer::class)
private class TvdbRef(val id: Int)

private object TvdbRefSerializer : KSerializer<TvdbRef> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TvdbRef")

    override fun deserialize(decoder: Decoder): TvdbRef {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        val o = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected an object")
        val p = o["id"] as? JsonPrimitive ?: throw SerializationException("bad id")
        val id = p.intOrNull ?: p.content.toIntOrNull() ?: throw SerializationException("bad id")
        return TvdbRef(id)
    }

    override fun serialize(encoder: Encoder, value: TvdbRef) {
        throw SerializationException("decode only")
    }
}

private class TvdbIds(val series: Int?, val movie: Int?)

/**
 * TheTVDB v4: episode list with thumbnails and clear-logo title art (spec: `TVDBClient.swift`). Needs the user's API
 * key (and PIN if it's a subscriber key). The login token is a secret ([SecretStore]), refreshed after 25 days or
 * on a 401.
 */
class TvdbClient(
    http: Http,
    private val apiKey: () -> String,
    private val pin: () -> String,
    private val secrets: SecretStore,
    cache: ResponseCache = ResponseCache.None,
    private val now: () -> Long = ::epochMillis,
) {
    private val http = http
    private val fetcher = CachedFetcher(http, cache, now)

    val hasKey: Boolean get() = apiKey().trim().isNotEmpty()

    // Login state. `authLock` is held for a whole login, so concurrent callers share one.
    private val authLock = Mutex()
    private var secretsLoaded = false
    private var token: String? = null
    private var tokenFor: String? = null
    private var tokenDate: Long? = null

    private val lock = Mutex()
    private val idCache = HashMap<String, TvdbIds>()
    private val episodeCache = HashMap<String, List<TvdbEpisode>>()

    private suspend fun authToken(): String = authLock.withLock {
        val who = apiKey().trim() + "|" + pin().trim()
        if (!secretsLoaded) {
            // The keyring can be slow (it may ask to be unlocked): never on the caller's thread.
            withContext(Dispatchers.Default) {
                token = secrets.get(SecretKeys.TVDB_TOKEN)
                tokenDate = secrets.get(SecretKeys.TVDB_TOKEN_DATE)?.toLongOrNull()
            }
            secretsLoaded = true
        }
        val t = token
        val d = tokenDate
        if (t != null && (tokenFor == null || tokenFor == who) && d != null && (now() - d) in 0 until 25 * Ttl.DAY) {
            tokenFor = who
            return@withLock t
        }
        val fields = LinkedHashMap<String, JsonPrimitive>()
        fields["apikey"] = JsonPrimitive(apiKey().trim())
        if (pin().trim().isNotEmpty()) fields["pin"] = JsonPrimitive(pin().trim())
        val reply = http.execute(
            HttpRequest(
                url = "$BASE/login",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = JsonObject(fields).toString(),
            ),
        )
        if (reply.status != 200) throw NoKeyException("TheTVDB")
        val fresh = PearJson.decodeFromString(TvdbEnvelope.serializer(TvdbLogin.serializer()), reply.text).data.token
        val stamp = now()
        token = fresh
        tokenFor = who
        tokenDate = stamp
        withContext(Dispatchers.Default) {
            secrets.set(SecretKeys.TVDB_TOKEN, fresh)
            secrets.set(SecretKeys.TVDB_TOKEN_DATE, stamp.toString())
        }
        fresh
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, ttl: Long = Ttl.HOUR * 6, retry: Boolean = true): T {
        if (!hasKey) throw NoKeyException("TheTVDB")
        return try {
            fetcher.fetch(
                key = "tvdb:$path",
                ttlMillis = ttl,
                // The token is only fetched when the network is actually needed.
                build = { HttpRequest(url = buildUrl(BASE + path), headers = mapOf("Authorization" to "Bearer ${authToken()}")) },
                parse = { PearJson.decodeFromString(TvdbEnvelope.serializer(serializer), it.decodeToString()).data },
            )
        } catch (e: BadStatusException) {
            if (e.status == 401 && retry) {
                authLock.withLock { token = null } // expired or revoked: log in again, once
                get(path, serializer, ttl, retry = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun ids(imdb: String): TvdbIds {
        lock.withLock { idCache[imdb] }?.let { return it }
        val remotes = attempt {
            get("/search/remoteid/${imdb.encodeURLPathPart()}", ListSerializer(TvdbRemote.serializer()), ttl = Ttl.DAY)
        }
        val out = TvdbIds(
            series = remotes?.firstNotNullOfOrNull { it.series }?.id,
            movie = remotes?.firstNotNullOfOrNull { it.movie }?.id,
        )
        if (out.series != null || out.movie != null) lock.withLock { idCache[imdb] = out }
        return out
    }

    /** Episodes of one season, in order. Empty when anything fails. */
    suspend fun episodes(imdb: String, season: Int): List<TvdbEpisode> {
        val key = "$imdb:$season"
        lock.withLock { episodeCache[key] }?.let { return it }
        val sid = ids(imdb).series ?: return emptyList()
        val page = attempt { get("/series/$sid/episodes/default?season=$season&page=0", TvdbEpisodePage.serializer()) } ?: return emptyList()
        val out = page.episodes.filter { it.seasonNumber == season }.sortedBy { it.number ?: 0 }
        lock.withLock { episodeCache[key] = out }
        return out
    }

    /** Title logo (PNG with transparency) for the detail header. Artwork type ids: series 23, movie 25. */
    suspend fun logo(imdb: String, type: String): String? {
        val i = ids(imdb)
        val arts: TvdbArts?
        if (type == "series") {
            val s = i.series ?: return null
            arts = attempt { get("/series/$s/artworks?type=23", TvdbArts.serializer()) }
        } else {
            val m = i.movie ?: return null
            arts = attempt { get("/movies/$m/extended", TvdbArts.serializer()) }
        }
        val want = if (type == "series") 23 else 25
        val best = (arts?.artworks ?: emptyList())
            .filter { it.type == want && (it.language == null || it.language == "eng") }
            .maxByOrNull { it.score ?: 0 }
        return best?.let { absolute(it.image) }
    }

    companion object {
        private const val BASE = "https://api4.thetvdb.com/v4"

        /** Artwork paths are relative to TheTVDB's artwork host. */
        fun absolute(s: String): String = if (s.startsWith("http")) s else "https://artworks.thetvdb.com$s"
    }
}

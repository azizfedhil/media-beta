package com.mediahub.pear.servers

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

data class PlexPin(val id: Int, val code: String) {
    val authUrl: String get() = "https://app.plex.tv/auth#?clientID=${ServerIdentity.deviceId()}&code=$code&context%5Bdevice%5D%5Bproduct%5D=Pear"
}

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

data class PlexServerInfo(
    val id: String,
    val name: String,
    val owned: Boolean,
    val token: String?,
    val connections: List<PlexConnection>,
)

object PlexAPI {
    fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", ServerIdentity.deviceId())
        put("X-Plex-Product", "Pear")
        put("X-Plex-Version", ServerIdentity.version)
        put("X-Plex-Platform", "Desktop")
        put("X-Plex-Device-Name", "Pear")
        if (token != null) put("X-Plex-Token", token)
    }

    suspend fun createPin(http: Http): PlexPin {
        val req = HttpRequest(
            url = "https://plex.tv/api/v2/pins?strong=true",
            method = "POST",
            headers = headers(),
            timeoutMillis = 10_000,
        )
        val reply = http.execute(req)
        if (reply.status != 200 && reply.status != 201) throw ServerError.Http(reply.status)
        val obj = PearJson.parseToJsonElement(reply.text).jObject() ?: throw ServerError.NotAServer
        val id = obj["id"].jInt() ?: throw ServerError.NotAServer
        val code = obj["code"].jStr() ?: throw ServerError.NotAServer
        return PlexPin(id, code)
    }

    suspend fun checkPin(http: Http, pin: PlexPin): String? {
        val req = HttpRequest(
            url = "https://plex.tv/api/v2/pins/${pin.id}",
            headers = headers(),
            timeoutMillis = 10_000,
        )
        val reply = attempt { http.execute(req) } ?: return null
        if (reply.status == 404) throw ServerError.Expired
        if (reply.status != 200) return null
        val obj = attempt { PearJson.parseToJsonElement(reply.text).jObject() } ?: return null
        val token = obj["authToken"].jStr()
        return if (token.isNullOrEmpty()) null else token
    }

    suspend fun servers(http: Http, token: String): List<PlexServerInfo> {
        val req = HttpRequest(
            url = "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1",
            headers = headers(token),
            timeoutMillis = 15_000,
        )
        val reply = http.execute(req)
        if (reply.status != 200) throw ServerError.Http(reply.status)
        val arr = attempt { PearJson.parseToJsonElement(reply.text) as? JsonArray } ?: return emptyList()
        return arr.mapNotNull { it.jObject() }.mapNotNull { r ->
            val provides = r["provides"].jStr() ?: ""
            if (!provides.split(',').contains("server")) return@mapNotNull null
            val id = r["clientIdentifier"].jStr() ?: return@mapNotNull null
            val name = r["name"].jStr() ?: return@mapNotNull null
            val owned = (r["owned"] as? JsonPrimitive)?.booleanOrNull ?: false
            val accessToken = r["accessToken"].jStr()
            val connsArr = r["connections"] as? JsonArray ?: return@mapNotNull null
            val conns = connsArr.mapNotNull { it.jObject() }.mapNotNull { c ->
                val uri = c["uri"].jStr() ?: return@mapNotNull null
                val local = (c["local"] as? JsonPrimitive)?.booleanOrNull ?: false
                val relay = (c["relay"] as? JsonPrimitive)?.booleanOrNull ?: false
                PlexConnection(uri, local, relay)
            }
            if (conns.isEmpty()) return@mapNotNull null
            PlexServerInfo(id, name, owned, accessToken, conns)
        }
    }

    fun rank(c: PlexConnection): Int = when {
        c.relay -> 4
        c.local -> if (c.uri.startsWith("https")) 0 else 1
        else -> if (c.uri.startsWith("https")) 2 else 3
    }

    suspend fun reachable(http: Http, connections: List<PlexConnection>): List<PlexConnection> = coroutineScope {
        connections.map { c ->
            async {
                val reply = attempt {
                    http.execute(HttpRequest(url = "${c.uri.trimEnd('/')}/identity", timeoutMillis = 4_000))
                }
                if (reply?.status == 200) c else null
            }
        }.awaitAll().filterNotNull()
    }
}

private data class PlexEntry(
    val key: String,
    val type: String, // "movie" or "series"
    val title: String,
    val year: Int?,
    val added: Int,
    val imdb: String?,
    val tmdb: String?,
    val summary: String?,
    val genres: List<String>,
    val thumb: String?,
)

class PlexBackend(
    private val http: Http,
    private val token: String,
    var base: String,
    private val alternates: List<String>,
) : MediaServerBackend {

    constructor(http: Http, account: MediaServerAccount, token: String) : this(
        http = http,
        token = token,
        base = account.baseURL.trimEnd('/'),
        alternates = account.alternates.map { it.trimEnd('/') },
    )

    private val lock = Mutex()
    private var index: List<PlexEntry> = emptyList()
    private var indexLoadedAt: Long = 0
    private val leafCache = HashMap<String, Pair<Long, List<JsonObject>>>()

    override suspend fun warm() {
        attempt { entries() }
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap(), timeoutMillis: Long = 8_000): JsonObject? {
        val candidates = (listOf(base) + alternates).distinct().take(3)
        for (root in candidates) {
            val url = buildUrl("$root/$path", query)
            val req = HttpRequest(
                url = url,
                headers = PlexAPI.headers(token),
                timeoutMillis = timeoutMillis,
            )
            val reply = attempt { http.execute(req) }
            if (reply != null && reply.status == 200) {
                if (root != base) base = root
                return attempt { PearJson.parseToJsonElement(reply.text).jObject() }
            }
        }
        return null
    }

    private suspend fun entries(): List<PlexEntry> = lock.withLock {
        val now = epochMillis()
        if (index.isNotEmpty() && now - indexLoadedAt < 900_000) return index
        val built = buildIndex()
        index = built
        indexLoadedAt = now
        built
    }

    private suspend fun buildIndex(): List<PlexEntry> {
        val sectionsRes = get("library/sections") ?: return index
        val container = sectionsRes["MediaContainer"].jObject() ?: return index
        val dir = container["Directory"] as? JsonArray ?: return index
        val out = mutableListOf<PlexEntry>()
        for (secElem in dir) {
            val sec = secElem.jObject() ?: continue
            val secKey = sec["key"].jStr() ?: continue
            val secType = sec["type"].jStr() ?: continue
            if (secType != "movie" && secType != "show") continue
            val kind = if (secType == "show") "series" else "movie"
            var start = 0
            while (true) {
                val pageRes = get(
                    "library/sections/$secKey/all",
                    mapOf(
                        "includeGuids" to "1",
                        "X-Plex-Container-Start" to start.toString(),
                        "X-Plex-Container-Size" to "500",
                    ),
                    timeoutMillis = 25_000,
                ) ?: break
                val pageContainer = pageRes["MediaContainer"].jObject() ?: break
                val metaArr = pageContainer["Metadata"] as? JsonArray ?: break
                metaArr.mapNotNull { it.jObject() }.forEach { parseEntry(it, kind)?.let { e -> out.add(e) } }
                val totalSize = pageContainer["totalSize"].jInt() ?: 0
                start += 500
                if (metaArr.isEmpty() || start >= totalSize) break
            }
        }
        return out
    }

    private fun parseEntry(item: JsonObject, kind: String): PlexEntry? {
        val key = item["ratingKey"].jStr() ?: return null
        val title = item["title"].jStr() ?: return null
        val year = item["year"].jInt()
        val added = item["addedAt"].jInt() ?: 0
        val summary = item["summary"].jStr()
        val thumb = item["thumb"].jStr()

        val guids = mutableListOf<String>()
        item["guid"].jStr()?.let { guids.add(it) }
        val guidArr = item["Guid"] as? JsonArray
        guidArr?.mapNotNull { it.jObject() }?.mapNotNull { it["id"].jStr() }?.let { guids.addAll(it) }

        fun extractId(prefix: String): String? {
            for (g in guids) {
                if (g.startsWith(prefix)) return g.removePrefix(prefix).takeWhile { it.isLetterOrDigit() }
            }
            return null
        }
        val imdb = extractId("imdb://")
        val tmdb = extractId("tmdb://") ?: extractId("themoviedb://")

        val genresArr = item["Genre"] as? JsonArray
        val genres = genresArr?.mapNotNull { it.jObject() }?.mapNotNull { it["tag"].jStr() } ?: emptyList()

        return PlexEntry(key, kind, title, year, added, imdb, tmdb, summary, genres, thumb)
    }

    override suspend fun streams(type: String, id: String): List<StreamItem> {
        val parts = id.split(':')
        val imdb = parts.firstOrNull() ?: return emptyList()
        if (!imdb.startsWith("tt")) return emptyList()
        val kind = if (type == "series") "series" else "movie"

        val all = entries()
        val hit = all.firstOrNull { it.type == kind && it.imdb.equals(imdb, ignoreCase = true) } ?: return emptyList()

        return if (kind == "movie") {
            val movieRes = get("library/metadata/${hit.key}") ?: return emptyList()
            val metaArr = movieRes["MediaContainer"].jObject()?.get("Metadata") as? JsonArray ?: return emptyList()
            metaArr.mapNotNull { it.jObject() }.flatMap { sources(it) }
        } else {
            if (parts.size != 3) return emptyList()
            val season = parts[1].toIntOrNull() ?: return emptyList()
            val episode = parts[2].toIntOrNull() ?: return emptyList()
            val eps = leaves(hit.key)
            val match = eps.firstOrNull { ep ->
                val pIdx = ep["parentIndex"].jInt()
                val idx = ep["index"].jInt()
                pIdx == season && idx == episode
            } ?: return emptyList()
            sources(match)
        }
    }

    private suspend fun leaves(showKey: String): List<JsonObject> {
        val now = epochMillis()
        val cached = leafCache[showKey]
        if (cached != null && now - cached.first < 600_000) return cached.second
        val res = get("library/metadata/$showKey/allLeaves", timeoutMillis = 20_000) ?: return emptyList()
        val metaArr = res["MediaContainer"].jObject()?.get("Metadata") as? JsonArray ?: return emptyList()
        val list = metaArr.mapNotNull { it.jObject() }
        leafCache[showKey] = now to list
        return list
    }

    private fun sources(item: JsonObject): List<StreamItem> {
        val mediaArr = item["Media"] as? JsonArray ?: return emptyList()
        return mediaArr.mapNotNull { it.jObject() }.mapNotNull { m ->
            val partArr = m["Part"] as? JsonArray ?: return@mapNotNull null
            val part = partArr.firstOrNull()?.jObject() ?: return@mapNotNull null
            val partKey = part["key"].jStr() ?: return@mapNotNull null
            val streamUrl = buildUrl("$base$partKey", mapOf("X-Plex-Token" to token))
            val file = part["file"].jStr()
            val container = part["container"].jStr() ?: m["container"].jStr()
            val ext = ServerFormat.fileExtension(file, container)
            val width = m["width"].jInt()
            val height = m["height"].jInt()
            val res = ServerFormat.resolution(width, height) ?: m["videoResolution"].jStr()?.uppercase()
            val label = ServerFormat.join(listOf(res, ext?.uppercase()))
            val vCodec = m["videoCodec"].jStr()
            val aCodec = m["audioCodec"].jStr()
            val aChannels = m["audioChannels"].jInt()
            val size = (part["size"] as? JsonPrimitive)?.longOrNull
            val bitrate = m["bitrate"].jInt()?.let { it * 1000 }
            val detail = ServerFormat.join(
                listOf(
                    ServerFormat.videoCodec(vCodec),
                    ServerFormat.audio(aCodec, aChannels),
                    ServerFormat.size(size),
                    ServerFormat.bitrate(bitrate),
                ),
            )
            createServerStreamItem(
                name = label.ifEmpty { "Plex" },
                detail = detail.ifEmpty { null },
                url = streamUrl,
                filename = ext?.let { "video.$it" },
            )
        }
    }

    override suspend fun catalog(type: String, id: String, skip: Int, search: String?): List<MetaPreview> {
        val kind = if (type == "series") "series" else "movie"
        val all = entries().filter { it.type == kind }
        val term = search?.trim()
        val filtered = if (!term.isNullOrEmpty()) {
            all.filter { it.title.contains(term, ignoreCase = true) }
                .sortedWith(compareBy<PlexEntry>({ !it.title.startsWith(term, ignoreCase = true) }, { it.title.lowercase() }))
        } else {
            all.sortedByDescending { it.added }
        }
        return filtered.drop(maxOf(skip, 0)).take(40).mapNotNull { e ->
            val posterUrl = e.thumb?.let { buildUrl("$base$it", mapOf("X-Plex-Token" to token)) }
            createServerMetaPreview(
                imdb = e.imdb,
                tmdb = e.tmdb,
                type = type,
                name = e.title,
                year = e.year,
                overview = e.summary,
                genres = e.genres,
                poster = posterUrl,
            )
        }
    }
}

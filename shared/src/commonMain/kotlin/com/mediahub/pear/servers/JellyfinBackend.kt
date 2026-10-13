package com.mediahub.pear.servers

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.model.jStrings
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class JellyfinPublicInfo(val name: String)
data class JellyfinSession(val token: String, val userId: String)
data class JellyfinQuickConnect(val code: String, val secret: String)

object JellyfinAPI {
    fun candidates(raw: String): List<String> {
        var s = raw.trim()
        if ('#' in s) s = s.substringBefore('#')
        for (tail in listOf("/web/index.html", "/web/", "/web")) {
            if (s.endsWith(tail)) { s = s.removeSuffix(tail); break }
        }
        s = s.trimEnd('/')
        if (s.isEmpty()) return emptyList()
        if ("://" in s) return listOf(s)
        val hostPart = s.substringBefore('/')
        val bare = hostPart.substringBefore(':')
        val isLan = ":" in hostPart || bare.endsWith(".local") ||
            bare.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val schemes = if (isLan) listOf("http", "https") else listOf("https", "http")
        return schemes.map { "$it://$s" }
    }

    fun authHeader(token: String?): String {
        var s = "MediaBrowser Client=\"Pear\", Device=\"Desktop\", DeviceId=\"${ServerIdentity.deviceId()}\", Version=\"${ServerIdentity.version}\""
        if (token != null) s += ", Token=\"$token\""
        return s
    }

    suspend fun probe(http: Http, raw: String): Pair<String, JellyfinPublicInfo> {
        val urls = candidates(raw)
        if (urls.isEmpty()) throw ServerError.BadAddress
        for (base in urls) {
            val url = "$base/System/Info/Public"
            val reply = attempt { http.execute(HttpRequest(url, timeoutMillis = 6_000)) } ?: continue
            if (reply.status != 200) continue
            val obj = attempt { PearJson.parseToJsonElement(reply.text).jObject() } ?: continue
            val name = obj["ServerName"].jStr() ?: obj["serverName"].jStr() ?: continue
            return base to JellyfinPublicInfo(name)
        }
        throw ServerError.NotAServer
    }

    suspend fun signIn(http: Http, base: String, username: String, password: String): JellyfinSession {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val req = HttpRequest(
            url = "$base/Users/AuthenticateByName",
            method = "POST",
            headers = mapOf(
                "Authorization" to authHeader(null),
                "Content-Type" to "application/json",
            ),
            body = body,
            timeoutMillis = 10_000,
        )
        val reply = http.execute(req)
        if (reply.status == 401 || reply.status == 403) throw ServerError.Unauthorized
        if (reply.status != 200) throw ServerError.Http(reply.status)
        val obj = PearJson.parseToJsonElement(reply.text).jObject() ?: throw ServerError.NotAServer
        val token = obj["AccessToken"].jStr() ?: obj["accessToken"].jStr() ?: throw ServerError.Unauthorized
        val userObj = obj["User"].jObject() ?: obj["user"].jObject() ?: throw ServerError.Unauthorized
        val userId = userObj["Id"].jStr() ?: userObj["id"].jStr() ?: throw ServerError.Unauthorized
        return JellyfinSession(token, userId)
    }

    suspend fun quickConnectStart(http: Http, base: String): JellyfinQuickConnect {
        val req = HttpRequest(
            url = "$base/QuickConnect/Initiate",
            method = "POST",
            headers = mapOf(
                "Authorization" to authHeader(null),
                "Content-Type" to "application/json",
            ),
            body = "{}",
            timeoutMillis = 10_000,
        )
        val reply = http.execute(req)
        if (reply.status == 401 || reply.status == 403) throw ServerError.QuickConnectOff
        if (reply.status != 200) throw ServerError.Http(reply.status)
        val obj = PearJson.parseToJsonElement(reply.text).jObject() ?: throw ServerError.NotAServer
        val code = obj["Code"].jStr() ?: obj["code"].jStr() ?: throw ServerError.NotAServer
        val secret = obj["Secret"].jStr() ?: obj["secret"].jStr() ?: throw ServerError.NotAServer
        return JellyfinQuickConnect(code, secret)
    }

    suspend fun quickConnectCheck(http: Http, base: String, secret: String): Boolean {
        val url = buildUrl("$base/QuickConnect/Connect", mapOf("secret" to secret))
        val req = HttpRequest(
            url = url,
            headers = mapOf("Authorization" to authHeader(null)),
            timeoutMillis = 10_000,
        )
        val reply = attempt { http.execute(req) } ?: return false
        if (reply.status != 200) return false
        val obj = attempt { PearJson.parseToJsonElement(reply.text).jObject() } ?: return false
        return obj["Authenticated"]?.let { it.jStr()?.toBoolean() ?: (it as? JsonPrimitive)?.booleanOrNull }
            ?: obj["authenticated"]?.let { it.jStr()?.toBoolean() ?: (it as? JsonPrimitive)?.booleanOrNull }
            ?: false
    }

    suspend fun quickConnectFinish(http: Http, base: String, secret: String): JellyfinSession {
        val body = buildJsonObject { put("Secret", secret) }.toString()
        val req = HttpRequest(
            url = "$base/Users/AuthenticateWithQuickConnect",
            method = "POST",
            headers = mapOf(
                "Authorization" to authHeader(null),
                "Content-Type" to "application/json",
            ),
            body = body,
            timeoutMillis = 10_000,
        )
        val reply = http.execute(req)
        if (reply.status != 200) throw ServerError.Http(reply.status)
        val obj = PearJson.parseToJsonElement(reply.text).jObject() ?: throw ServerError.NotAServer
        val token = obj["AccessToken"].jStr() ?: obj["accessToken"].jStr() ?: throw ServerError.Unauthorized
        val userObj = obj["User"].jObject() ?: obj["user"].jObject() ?: throw ServerError.Unauthorized
        val userId = userObj["Id"].jStr() ?: userObj["id"].jStr() ?: throw ServerError.Unauthorized
        return JellyfinSession(token, userId)
    }
}

class JellyfinBackend(
    private val http: Http,
    val base: String,
    val token: String,
    val userId: String,
) : MediaServerBackend {

    constructor(http: Http, account: MediaServerAccount, token: String) : this(
        http = http,
        base = account.baseURL.trimEnd('/'),
        token = token,
        userId = account.userID ?: "",
    )

    private suspend fun get(path: String, query: Map<String, String>): JsonObject? {
        val url = buildUrl("$base/$path", query)
        val req = HttpRequest(
            url = url,
            headers = mapOf(
                "Authorization" to JellyfinAPI.authHeader(token),
                "Accept" to "application/json",
            ),
            timeoutMillis = 12_000,
        )
        val reply = attempt { http.execute(req) } ?: return null
        if (reply.status != 200) return null
        return attempt { PearJson.parseToJsonElement(reply.text).jObject() }
    }

    override suspend fun streams(type: String, id: String): List<StreamItem> {
        val parts = id.split(':')
        val imdb = parts.firstOrNull() ?: return emptyList()
        if (!imdb.startsWith("tt")) return emptyList()

        return if (type == "series") {
            if (parts.size != 3) return emptyList()
            val season = parts[1].toIntOrNull() ?: return emptyList()
            val episode = parts[2].toIntOrNull() ?: return emptyList()

            val show = find(imdb, "Series").firstOrNull() ?: return emptyList()
            val showId = show["Id"].jStr() ?: show["id"].jStr() ?: return emptyList()
            val epsRes = get(
                "Shows/$showId/Episodes",
                mapOf(
                    "userId" to userId,
                    "season" to season.toString(),
                    "fields" to "MediaSources",
                ),
            ) ?: return emptyList()
            val items = epsRes["Items"] as? JsonArray ?: return emptyList()
            val hit = items.mapNotNull { it.jObject() }.firstOrNull { obj ->
                val start = obj["IndexNumber"].jInt() ?: obj["indexNumber"].jInt() ?: -1
                val end = obj["IndexNumberEnd"].jInt() ?: obj["indexNumberEnd"].jInt() ?: start
                start <= episode && episode <= end
            } ?: return emptyList()
            sources(hit)
        } else {
            val movie = find(imdb, "Movie").firstOrNull() ?: return emptyList()
            sources(movie)
        }
    }

    private suspend fun find(imdb: String, kind: String): List<JsonObject> {
        val res = get(
            "Items",
            mapOf(
                "userId" to userId,
                "Recursive" to "true",
                "IncludeItemTypes" to kind,
                "AnyProviderIdEquals" to "imdb.$imdb",
                "Fields" to "MediaSources,ProviderIds",
                "Limit" to "5",
            ),
        ) ?: return emptyList()
        val items = res["Items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { it.jObject() }.filter { obj ->
            val pids = obj["ProviderIds"].jObject() ?: obj["providerIds"].jObject()
            val found = pids?.entries?.firstOrNull { it.key.equals("imdb", ignoreCase = true) }?.value.jStr()
            found?.equals(imdb, ignoreCase = true) == true
        }
    }

    private fun sources(item: JsonObject): List<StreamItem> {
        val itemId = item["Id"].jStr() ?: item["id"].jStr() ?: return emptyList()
        val mediaSources = item["MediaSources"] as? JsonArray ?: item["mediaSources"] as? JsonArray ?: return emptyList()
        return mediaSources.mapNotNull { it.jObject() }.mapNotNull { src ->
            val srcId = src["Id"].jStr() ?: src["id"].jStr() ?: itemId
            val streamUrl = buildUrl(
                "$base/Videos/$itemId/stream",
                mapOf(
                    "static" to "true",
                    "MediaSourceId" to srcId,
                    "api_key" to token,
                ),
            )
            val path = src["Path"].jStr() ?: src["path"].jStr()
            val container = src["Container"].jStr() ?: src["container"].jStr()
            val ext = ServerFormat.fileExtension(path, container)
            val streams = src["MediaStreams"] as? JsonArray ?: src["mediaStreams"] as? JsonArray
            var width: Int? = null
            var height: Int? = null
            var vCodec: String? = null
            var aCodec: String? = null
            var aChannels: Int? = null
            streams?.mapNotNull { it.jObject() }?.forEach { st ->
                val stType = st["Type"].jStr() ?: st["type"].jStr()
                if (stType.equals("Video", ignoreCase = true)) {
                    width = st["Width"].jInt() ?: st["width"].jInt()
                    height = st["Height"].jInt() ?: st["height"].jInt()
                    vCodec = st["Codec"].jStr() ?: st["codec"].jStr()
                } else if (stType.equals("Audio", ignoreCase = true) && aCodec == null) {
                    aCodec = st["Codec"].jStr() ?: st["codec"].jStr()
                    aChannels = st["Channels"].jInt() ?: st["channels"].jInt()
                }
            }
            val res = ServerFormat.resolution(width, height)
            val label = ServerFormat.join(listOf(res, ext?.uppercase()))
            val srcName = src["Name"].jStr() ?: src["name"].jStr()
            val sizeBytes = (src["Size"] as? JsonPrimitive)?.longOrNull
            val bitrate = src["Bitrate"].jInt() ?: src["bitrate"].jInt()
            val detail = ServerFormat.join(
                listOf(
                    ServerFormat.videoCodec(vCodec),
                    ServerFormat.audio(aCodec, aChannels),
                    ServerFormat.size(sizeBytes),
                    ServerFormat.bitrate(bitrate),
                ),
            )
            createServerStreamItem(
                name = label.ifEmpty { srcName ?: "Jellyfin" },
                detail = detail.ifEmpty { null },
                url = streamUrl,
                filename = ext?.let { "video.$it" },
            )
        }
    }

    override suspend fun catalog(type: String, id: String, skip: Int, search: String?): List<MetaPreview> {
        val kind = if (type == "series") "Series" else "Movie"
        val q = mutableMapOf(
            "userId" to userId,
            "Recursive" to "true",
            "IncludeItemTypes" to kind,
            "Fields" to "ProviderIds,Overview,Genres",
            "Limit" to "40",
            "StartIndex" to maxOf(skip, 0).toString(),
            "EnableTotalRecordCount" to "false",
        )
        val term = search?.trim()
        if (!term.isNullOrEmpty()) {
            q["searchTerm"] = term
        } else {
            q["SortBy"] = "DateCreated"
            q["SortOrder"] = "Descending"
        }
        val res = get("Items", q) ?: return emptyList()
        val items = res["Items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { it.jObject() }.mapNotNull { preview(it, type) }
    }

    private fun preview(item: JsonObject, type: String): MetaPreview? {
        val itemId = item["Id"].jStr() ?: item["id"].jStr() ?: return null
        val pids = item["ProviderIds"].jObject() ?: item["providerIds"].jObject()
        val imdb = pids?.entries?.firstOrNull { it.key.equals("imdb", ignoreCase = true) }?.value.jStr()
        val tmdb = pids?.entries?.firstOrNull { it.key.equals("tmdb", ignoreCase = true) }?.value.jStr()
        val name = item["Name"].jStr() ?: item["name"].jStr() ?: "Untitled"
        val year = item["ProductionYear"].jInt() ?: item["productionYear"].jInt()
        val overview = item["Overview"].jStr() ?: item["overview"].jStr()
        val genres = (item["Genres"] ?: item["genres"]).jStrings() ?: emptyList()
        val imageTags = item["ImageTags"].jObject() ?: item["imageTags"].jObject()
        val hasPrimary = imageTags?.entries?.any { it.key.equals("primary", ignoreCase = true) } == true
        val poster = if (hasPrimary) buildUrl("$base/Items/$itemId/Images/Primary", mapOf("maxWidth" to "500")) else null

        return createServerMetaPreview(
            imdb = imdb,
            tmdb = tmdb,
            type = type,
            name = name,
            year = year,
            overview = overview,
            genres = genres,
            poster = poster,
        )
    }
}

package com.mediahub.pear.servers

import com.mediahub.pear.appVersion
import com.mediahub.pear.data.GenreNames
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.randomProfileId
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.net.MetaException
import com.mediahub.pear.net.attempt
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlin.math.round

enum class MediaServerKind(val title: String, val symbol: String) {
    Jellyfin("Jellyfin", "server.rack"),
    Plex("Plex", "play.rectangle.fill"),
}

@Serializable
data class MediaServerAccount(
    val id: String,
    val kind: MediaServerKind,
    var name: String,
    var baseURL: String,
    var alternates: List<String> = emptyList(),
    var userID: String? = null,
    var serverID: String? = null,
    var enabled: Boolean = true,
    var showOnHome: Boolean = true,
) {
    val host: String get() = attempt { Url(baseURL).host } ?: baseURL
}

sealed class ServerError(message: String) : MetaException(message) {
    object BadAddress : ServerError("That address doesn't look right.")
    object NotAServer : ServerError("Nothing that looks like a Jellyfin server answered at that address.")
    object Unreachable : ServerError("Couldn't reach the server.")
    object Unauthorized : ServerError("The server refused the sign-in. Check the username and password.")
    object QuickConnectOff : ServerError("Quick Connect isn't turned on for this server.")
    object Expired : ServerError("The sign-in code expired. Try again.")
    class Http(val code: Int) : ServerError("The server answered with an error (HTTP $code).")
}

interface MediaServerBackend {
    suspend fun streams(type: String, id: String): List<StreamItem>
    suspend fun catalog(type: String, id: String, skip: Int, search: String?): List<MetaPreview>
    suspend fun warm() {}
}

object MediaServerRegistry {
    private val table = HashMap<String, MediaServerBackend>()

    @Synchronized
    fun set(newTable: Map<String, MediaServerBackend>) {
        table.clear()
        table.putAll(newTable)
    }

    @Synchronized
    fun backend(addon: Addon): MediaServerBackend? = table[addon.id]
}

object ServerIdentity {
    private var cachedId: String? = null
    fun deviceId(prefs: PrefStore? = null): String {
        cachedId?.let { return it }
        val saved = prefs?.string("mediaserver.deviceID")
        if (!saved.isNullOrEmpty()) {
            cachedId = saved
            return saved
        }
        val newId = randomProfileId().lowercase()
        prefs?.setString("mediaserver.deviceID", newId)
        cachedId = newId
        return newId
    }
    /** The app version, sent to Jellyfin and Plex as the client version. */
    val version: String get() = appVersion
}

object ServerFormat {
    fun resolution(width: Int?, height: Int?): String? {
        val h = height ?: return null
        if (h <= 0) return null
        val w = width ?: 0
        return when {
            h >= 2000 || w >= 3500 -> "4K"
            h >= 1000 || w >= 1800 -> "1080p"
            h >= 700 || w >= 1200 -> "720p"
            else -> "${h}p"
        }
    }

    fun videoCodec(raw: String?): String? {
        val r = raw?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (r) {
            "h264", "avc", "avc1" -> "H.264"
            "hevc", "h265" -> "HEVC"
            "mpeg4" -> "MPEG-4"
            "vc1" -> "VC-1"
            else -> r.uppercase()
        }
    }

    fun audio(codec: String?, channels: Int?): String? {
        val r = codec?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val names = mapOf(
            "aac" to "AAC", "ac3" to "AC3", "eac3" to "EAC3", "dts" to "DTS",
            "truehd" to "TrueHD", "flac" to "FLAC", "opus" to "Opus", "mp3" to "MP3",
        )
        val name = names[r] ?: r.uppercase()
        if (channels == null || channels <= 0) return name
        val layout = when (channels) {
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }
        return "$name $layout"
    }

    fun size(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val gb = bytes / 1_000_000_000.0
        val mb = bytes / 1_000_000.0
        return when {
            gb >= 1.0 -> "${round(gb * 10) / 10} GB"
            mb >= 1.0 -> "${round(mb * 10) / 10} MB"
            else -> "${bytes / 1_000} KB"
        }
    }

    fun bitrate(bps: Int?): String? {
        if (bps == null || bps <= 0) return null
        val mbps = bps / 1_000_000.0
        return "${round(mbps * 10) / 10} Mbps"
    }

    fun join(parts: List<String?>): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

    fun fileExtension(path: String?, container: String?): String? {
        if (path != null) {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext.length <= 5) return ext
        }
        return container?.split(',')?.firstOrNull()?.trim()?.lowercase()
    }
}

fun createServerAddon(account: MediaServerAccount): Addon {
    val id = "pear.server.${account.kind.name.lowercase()}.${account.id}"
    val manifestUrl = "pear-server://${account.id}/manifest.json"
    val extra = listOf(
        AddonManifest.Extra("search", isRequired = !account.showOnHome),
        AddonManifest.Extra("skip", isRequired = false),
    )
    fun catalogDef(type: String, label: String) = AddonManifest.CatalogDef(
        type = type,
        id = "recent",
        name = "${account.name} — $label",
        extra = extra,
    )
    val manifest = AddonManifest(
        id = id,
        name = "${account.kind.title} · ${account.name}",
        description = "${account.kind.title} library",
        types = listOf("movie", "series"),
        resources = listOf(
            AddonManifest.Resource("stream", types = listOf("movie", "series"), idPrefixes = listOf("tt")),
            AddonManifest.Resource("catalog"),
        ),
        catalogs = listOf(catalogDef("movie", "New"), catalogDef("series", "New")),
    )
    return Addon(manifestUrl, manifest)
}

fun createServerStreamItem(name: String, detail: String?, url: String, filename: String? = null): StreamItem =
    StreamItem(
        name = name,
        description = detail,
        url = url,
        behaviorHints = filename?.let { StreamItem.Hints(filename = it) },
    )

fun createServerMetaPreview(
    imdb: String?,
    tmdb: String?,
    type: String,
    name: String,
    year: Int?,
    overview: String?,
    genres: List<String>,
    poster: String?,
): MetaPreview? {
    val id = when {
        imdb != null && imdb.startsWith("tt") -> imdb
        tmdb != null && tmdb.toIntOrNull() != null -> "tmdb:$tmdb"
        else -> return null
    }
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = if (id.startsWith("tt")) null else poster,
        description = overview,
        releaseInfo = year?.toString(),
        genreIDs = if (genres.isEmpty()) null else GenreNames.ids(genres),
    )
}

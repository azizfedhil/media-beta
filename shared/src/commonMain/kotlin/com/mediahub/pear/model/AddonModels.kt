package com.mediahub.pear.model

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.net.BadUrlException
import com.mediahub.pear.net.attempt
import io.ktor.http.Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * What an add-on's `manifest.json` says (spec: `AddonManifest` in Models.swift). Read tolerantly: add-ons are
 * inconsistent, so a field of the wrong kind is treated as missing, and a catalogue or resource entry that can't be
 * read is skipped instead of failing the whole add-on. Only `id` and `name` are required.
 */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val types: List<String>? = null,
    val catalogs: List<CatalogDef>? = null,
    val resources: List<Resource>? = null,
    val idPrefixes: List<String>? = null,
    val behaviorHints: BehaviorHints? = null,
) {
    /** Only the hints the app uses. */
    data class BehaviorHints(val configurable: Boolean? = null, val configurationRequired: Boolean? = null)

    data class Extra(val name: String, val isRequired: Boolean? = null)

    data class CatalogDef(
        val type: String,
        val id: String,
        val name: String? = null,
        val extra: List<Extra>? = null,
        /** Older manifests declare extras as plain string lists instead of `extra`. */
        val extraSupported: List<String>? = null,
        val extraRequired: List<String>? = null,
    ) {
        val supportedExtras: Set<String>
            get() = (extra.orEmpty().map { it.name } + extraSupported.orEmpty() + extraRequired.orEmpty()).toSet()

        val requiredExtras: Set<String>
            get() = (extra.orEmpty().filter { it.isRequired == true }.map { it.name } + extraRequired.orEmpty()).toSet()

        /** Home rows can't satisfy required params (for example search-only catalogues). */
        val isBrowsable: Boolean get() = requiredExtras.isEmpty()
        val supportsSkip: Boolean get() = "skip" in supportedExtras

        /** Can answer `search=<query>` without needing any other parameter. */
        val isSearchable: Boolean get() = "search" in supportedExtras && (requiredExtras - "search").isEmpty()
    }

    /** `resources` is either `["stream"]` or `[{name, types, idPrefixes}]`. */
    data class Resource(val name: String, val types: List<String>? = null, val idPrefixes: List<String>? = null)

    companion object {
        fun parse(text: String): AddonManifest {
            val o = PearJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("A manifest must be an object")
            val id = o["id"].jStr() ?: throw SerializationException("Manifest: missing \"id\"")
            val name = o["name"].jStr() ?: throw SerializationException("Manifest: missing \"name\"")
            val hints = o["behaviorHints"].jObject()?.let {
                BehaviorHints(configurable = it["configurable"].jBool(), configurationRequired = it["configurationRequired"].jBool())
            }
            return AddonManifest(
                id = id,
                name = name,
                version = o["version"].jStr(),
                description = o["description"].jStr(),
                logo = o["logo"].jStr(),
                types = o["types"].jStrings(),
                catalogs = (o["catalogs"] as? JsonArray)?.mapNotNull { parseCatalog(it) },
                resources = (o["resources"] as? JsonArray)?.mapNotNull { parseResource(it) },
                idPrefixes = o["idPrefixes"].jStrings(),
                behaviorHints = hints,
            )
        }

        private fun parseCatalog(el: kotlinx.serialization.json.JsonElement): CatalogDef? {
            val c = el as? JsonObject ?: return null
            val type = c["type"].jStr() ?: return null
            val id = c["id"].jStr() ?: return null
            val extra = (c["extra"] as? JsonArray)?.mapNotNull { e ->
                val eo = e as? JsonObject ?: return@mapNotNull null
                val n = eo["name"].jStr() ?: return@mapNotNull null
                Extra(n, eo["isRequired"].jBool())
            }
            return CatalogDef(
                type = type,
                id = id,
                name = c["name"].jStr(),
                extra = extra,
                extraSupported = c["extraSupported"].jStrings(),
                extraRequired = c["extraRequired"].jStrings(),
            )
        }

        private fun parseResource(el: kotlinx.serialization.json.JsonElement): Resource? {
            if (el is JsonPrimitive) return el.jStr()?.let { Resource(it) }
            val o = el as? JsonObject ?: return null
            val name = o["name"].jStr() ?: return null
            return Resource(name, o["types"].jStrings(), o["idPrefixes"].jStrings())
        }
    }
}

/**
 * An installed add-on: its address and what its manifest said (spec: `Addon`). The [id] is the manifest address, so it
 * is as private as the address is: debrid add-on addresses hold API keys. Never log it, and never use it as a cache key
 * as it is (the client hashes it).
 */
data class Addon(val manifestUrl: String, val manifest: AddonManifest) {
    val id: String get() = manifestUrl

    /** The address up to and including the last `/`: where `catalog/...`, `meta/...` and `stream/...` live. */
    val baseUrl: String get() = manifestUrl.substringBeforeLast('/') + "/"

    val homeCatalogs: List<AddonManifest.CatalogDef> get() = manifest.catalogs.orEmpty().filter { it.isBrowsable }

    /**
     * AIOMetadata (github.com/cedya77/aiometadata) is recognised by name: its manifest id, name, description or the
     * host it is served from says so ("AIOMetadata", "aio-metadata"...). Only the host is looked at, never the path,
     * because add-on addresses can embed secrets. A self-hosted copy under another name can be switched on by hand
     * (`AddonStore.setCatalogPicker`).
     */
    val isAIOMetadata: Boolean
        get() {
            val host = attempt { Url(manifestUrl).host } ?: ""
            val fields = listOf(manifest.id, manifest.name, manifest.description ?: "", host)
            return fields.any { it.lowercase().filter(Char::isLetter).contains("aiometadata") }
        }

    /** The add-on says it has a setup page (`behaviorHints.configurable`), or can't work without one. */
    val isConfigurable: Boolean
        get() = manifest.behaviorHints?.configurable == true || manifest.behaviorHints?.configurationRequired == true

    val needsConfiguration: Boolean get() = manifest.behaviorHints?.configurationRequired == true

    /** Stremio convention: the setup page sits next to the manifest (`.../manifest.json` -> `.../configure`). */
    val configureUrl: String get() = baseUrl + "configure"

    fun provides(resource: String, type: String, id: String): Boolean {
        val r = manifest.resources?.firstOrNull { it.name == resource } ?: return false
        if (r.types != null && type !in r.types) return false
        val prefixes = r.idPrefixes ?: manifest.idPrefixes
        if (prefixes != null && prefixes.none { id.startsWith(it) }) return false
        return true
    }

    companion object {
        /** Turns what the user pasted into the manifest address. Accepts `stremio://` and addresses without `manifest.json`. */
        fun normalize(input: String): String {
            var s = input.trim()
            if (s.startsWith("stremio://")) s = "https://" + s.removePrefix("stremio://")
            if (!s.endsWith("manifest.json")) s = s.trim('/') + "/manifest.json"
            val web = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
            if (!web || s.any { it.isWhitespace() } || attempt { Url(s) } == null) throw BadUrlException()
            return s
        }
    }
}

/** One playable (or not) source from an add-on's `stream` resource (spec: `StreamItem`). Read tolerantly. */
data class StreamItem(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    /** Directly playable. */
    val url: String? = null,
    /** Torrent: only playable through the opt-in P2P engine (Batch 4.5). */
    val infoHash: String? = null,
    /** Torrentio: which file inside the torrent to play (season packs). */
    val fileIdx: Int? = null,
    /** Torrentio: `tracker:<url>` / `dht:<hash>` entries. */
    val sources: List<String>? = null,
    val externalUrl: String? = null,
    val behaviorHints: Hints? = null,
) {
    /** Optional add-on hints. Some debrid add-ons require custom request headers to fetch the file. */
    data class Hints(val filename: String? = null, val proxyRequestHeaders: Map<String, String>? = null)

    /** A stable id for lists. Sources with no link at all get a random one (Swift made a new one on every read). */
    val id: String = url ?: infoHash ?: externalUrl ?: ("stream-" + Random.nextLong().toString(16))

    val requestHeaders: Map<String, String> get() = behaviorHints?.proxyRequestHeaders ?: emptyMap()

    /** What a pinned source is matched by: the add-on's display name, because stream links change per episode. */
    val signature: String get() = (name ?: title ?: "").trim()

    /** A raw BitTorrent stream: a valid info hash and no direct link. Anything with a `url` (debrid, HTTPS) is never a torrent here. */
    val isTorrent: Boolean
        get() {
            val h = infoHash
            return url == null && h != null && h.length == 40 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

    /** Torrentio's `sources` look like `tracker:udp://host:port/announce` or `dht:<hash>`. Only trackers are kept. */
    val trackers: List<String>
        get() = sources.orEmpty().mapNotNull { if (it.startsWith("tracker:")) it.removePrefix("tracker:") else null }

    /** Whether it can be played right now: a direct link, or a torrent while the P2P engine is ready ([p2pReady]). */
    fun isPlayable(p2pReady: Boolean): Boolean = url != null || (p2pReady && isTorrent)

    /** Lower-case extension of the file name hint, or of the link's last path part. Empty when there is none. */
    val fileExtension: String
        get() {
            val fromUrl = url?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')
            val f = behaviorHints?.filename ?: fromUrl ?: ""
            return f.substringAfterLast('.', "").lowercase()
        }

    companion object {
        /** One entry of a `streams` array, or null when it isn't an object. */
        fun parse(el: kotlinx.serialization.json.JsonElement): StreamItem? {
            val o = el as? JsonObject ?: return null
            val hints = o["behaviorHints"].jObject()?.let { h ->
                val headers = h["proxyHeaders"].jObject()?.get("request").jObject()?.let { r ->
                    val m = LinkedHashMap<String, String>()
                    var ok = true
                    for ((k, v) in r) {
                        val s = v.jStr()
                        if (s == null) ok = false else m[k] = s
                    }
                    if (ok) m else null
                }
                Hints(filename = h["filename"].jStr(), proxyRequestHeaders = headers)
            }
            val idx = o["fileIdx"].jInt() ?: o["fileIdx"].jStr()?.toIntOrNull()
            return StreamItem(
                name = o["name"].jStr(),
                title = o["title"].jStr(),
                description = o["description"].jStr(),
                url = o["url"].jStr(),
                infoHash = o["infoHash"].jStr(),
                fileIdx = idx,
                sources = o["sources"].jStrings(),
                externalUrl = o["externalUrl"].jStr(),
                behaviorHints = hints,
            )
        }

        /** The `streams` array of an add-on answer. An entry that can't be read is skipped. */
        fun decodeStreams(text: String): List<StreamItem> {
            val root = PearJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("Expected an object")
            val arr = root["streams"] as? JsonArray ?: throw SerializationException("No streams")
            return arr.mapNotNull { parse(it) }
        }
    }
}

/** Where a row came from, so its "See all" page knows how to load more. */
sealed interface CatalogSource {
    data object None : CatalogSource
    /** [kind] is `movie` or `tv`. */
    data class TmdbTrending(val kind: String) : CatalogSource
    data class FromAddon(val addon: Addon, val catalog: AddonManifest.CatalogDef) : CatalogSource
}

class CatalogRow(
    val id: String,
    val title: String,
    val items: List<MetaPreview>,
    val source: CatalogSource = CatalogSource.None,
    /** Icon name shown next to the row title on Home (the iOS SF Symbol name; the icon map translates it). */
    val symbol: String? = null,
    /** Stable id the user's "hide this row" choice is stored under (`addonRowKey`). null = can't be hidden. */
    val prefKey: String? = null,
    /** How many titles the source served before the content filter removed some. Its `skip` cursor for "See all". */
    val rawCount: Int? = null,
) {
    // Identity only: rows are navigation values, not data to compare.
    override fun equals(other: Any?) = other is CatalogRow && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "CatalogRow($id, ${items.size} titles)"
}

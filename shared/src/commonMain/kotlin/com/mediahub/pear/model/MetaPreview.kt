package com.mediahub.pear.model

import com.mediahub.pear.data.GenreNames
import com.mediahub.pear.data.PearJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A title in a list: from an add-on catalogue, TMDB, or MDBList (spec: `MetaPreview` in Models.swift).
 * TMDB titles have the id `tmdb:<id>`; the IMDb id is resolved when a title is opened.
 * The JSON shape is the Swift one (see [MetaPreviewSerializer]), because history and library entries are stored as it.
 */
@Serializable(with = MetaPreviewSerializer::class)
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    /** 0-10. IMDb rating for add-on items (`imdbRating`), TMDB vote average for TMDB items. */
    val rating: Double? = null,
    /** TMDB genre ids, straight from TMDB or mapped from the genre names an add-on lists. null = the source didn't say. */
    val genreIDs: List<Int>? = null,
    /** Original language and countries of origin (TMDB items). Tells anime from other animation. */
    val language: String? = null,
    val countries: List<String>? = null,
) {
    val posterUrl: String? get() = poster.usable() ?: metahub("poster")
    val backdropUrl: String? get() = background.usable() ?: metahub("background") ?: poster.usable()

    private fun metahub(kind: String): String? =
        if (id.startsWith("tt")) "https://images.metahub.space/$kind/medium/$id/img" else null

    /** First four digits of [releaseInfo] ("2021", "2019-2022", "2019-"). */
    val year: Int?
        get() {
            val r = releaseInfo?.trim() ?: return null
            return if (r.length >= 4) r.take(4).toIntOrNull() else null
        }

    /** Which service the [rating] number comes from. */
    val ratingLabel: String get() = if (id.startsWith("tmdb:")) "TMDB" else "IMDb"
    val typeLabel: String get() = if (type == "series") "Series" else "Movie"

    fun withRating(r: Double?): MetaPreview = copy(rating = r)

    /** Same title with another logo (everything else untouched). */
    fun withLogo(l: String?): MetaPreview = copy(logo = l)

    /** Bigger artwork for full-width hero cards (the list sizes are tuned for small posters). */
    fun heroUrl(wide: Boolean): String? {
        val raw = if (wide) (backdropUrl ?: posterUrl) else (posterUrl ?: backdropUrl)
        val s = raw ?: return null
        return when {
            "/t/p/w342/" in s -> s.replace("/t/p/w342/", "/t/p/w780/")
            "/t/p/w780/" in s -> s.replace("/t/p/w780/", "/t/p/w1280/")
            "metahub.space/" in s -> s.replace("/medium/", "/large/")
            else -> s
        }
    }

    companion object {
        /**
         * The `metas` array of an add-on answer or of a saved list. An item that can't be read (no id, name or type)
         * is skipped instead of failing the whole catalogue.
         */
        fun decodeMetas(text: String): List<MetaPreview> {
            val root = PearJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("Expected an object")
            val metas = root["metas"] as? JsonArray ?: throw SerializationException("No metas")
            return metas.mapNotNull { runCatching { PearJson.decodeFromJsonElement(MetaPreviewSerializer, it) }.getOrNull() }
        }
    }
}

private fun String?.usable(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Hand-written so one odd field (rating as a string, year as a number...) never drops a whole catalogue.
 * Reads what add-ons send (`imdbRating`, `genres` or `genre`) and what this app writes (`tmdbGenres`, `lang`,
 * `countries`); writes only the latter, and only the fields that are set, like Swift's `JSONEncoder`.
 */
object MetaPreviewSerializer : KSerializer<MetaPreview> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MetaPreview")

    override fun deserialize(decoder: Decoder): MetaPreview {
        val input = decoder as? JsonDecoder ?: throw SerializationException("MetaPreview is JSON only")
        val o = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("MetaPreview must be an object")
        fun req(key: String) = o[key].str() ?: throw SerializationException("MetaPreview: missing \"$key\"")

        val releaseInfo = o["releaseInfo"].str() ?: o["releaseInfo"].int()?.toString()
        val r = o["rating"].number() ?: o["imdbRating"].number()
        return MetaPreview(
            id = req("id"),
            type = req("type"),
            name = req("name"),
            poster = o["poster"].str(),
            background = o["background"].str(),
            logo = o["logo"].str(),
            description = o["description"].str(),
            releaseInfo = releaseInfo,
            rating = if ((r ?: 0.0) > 0) r else null,
            genreIDs = o["tmdbGenres"].intList() ?: mappedGenres(o),
            language = o["lang"].str(),
            countries = o["countries"].stringList(),
        )
    }

    override fun serialize(encoder: Encoder, value: MetaPreview) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("MetaPreview is JSON only")
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(value.id)
        m["type"] = JsonPrimitive(value.type)
        m["name"] = JsonPrimitive(value.name)
        value.poster?.let { m["poster"] = JsonPrimitive(it) }
        value.background?.let { m["background"] = JsonPrimitive(it) }
        value.logo?.let { m["logo"] = JsonPrimitive(it) }
        value.description?.let { m["description"] = JsonPrimitive(it) }
        value.releaseInfo?.let { m["releaseInfo"] = JsonPrimitive(it) }
        value.rating?.let { m["rating"] = JsonPrimitive(it) }
        value.genreIDs?.let { ids -> m["tmdbGenres"] = JsonArray(ids.map { JsonPrimitive(it) }) }
        value.language?.let { m["lang"] = JsonPrimitive(it) }
        value.countries?.let { c -> m["countries"] = JsonArray(c.map { JsonPrimitive(it) }) }
        output.encodeJsonElement(JsonObject(m))
    }

    /**
     * Only the genres the content filter cares about are kept, as TMDB ids. null when the add-on listed none:
     * an empty list usually means "unknown", not "has no genre".
     */
    private fun mappedGenres(o: JsonObject): List<Int>? {
        val list = o["genres"].stringList()
        val older = o["genre"].stringList()
        val single = o["genre"].str()
        val names = list ?: older ?: single?.let { listOf(it) }
        if (names.isNullOrEmpty()) return null
        return GenreNames.ids(names)
    }
}

// Tolerant readers: a value of the wrong kind is "not there", never an error.

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.intOrNull

private fun JsonElement?.number(): Double? {
    val p = this as? JsonPrimitive ?: return null
    return when {
        p is JsonNull -> null
        p.isString -> p.content.toDoubleOrNull()
        else -> p.doubleOrNull
    }
}

/** A list in which every entry is a string; anything else is "not there" (like decoding `[String]` with `try?`). */
private fun JsonElement?.stringList(): List<String>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<String>(a.size)
    for (e in a) out.add(e.str() ?: return null)
    return out
}

private fun JsonElement?.intList(): List<Int>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<Int>(a.size)
    for (e in a) out.add(e.int() ?: return null)
    return out
}

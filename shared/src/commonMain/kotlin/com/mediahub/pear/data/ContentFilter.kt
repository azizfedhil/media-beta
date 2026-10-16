package com.mediahub.pear.data

import com.mediahub.pear.model.MetaPreview
import kotlinx.serialization.Serializable

/*
 * The decision logic of `ContentRules.swift`, added to the data classes of ContentPrefs.kt. Pure functions, no I/O.
 * (Batch 2.1 ported the stored shape; the TMDB client needs the decisions, so they arrive with it.)
 */

/** TMDB genre ids that put a title in this category. null for Anime, Cartoons and the regions, which depend on origin. */
val ContentCategory.genreIDs: Set<Int>?
    get() = when (this) {
        ContentCategory.Anime, ContentCategory.Cartoons, ContentCategory.Asian, ContentCategory.Indian, ContentCategory.Foreign -> null
        ContentCategory.Kids -> setOf(10762, 10751) // Kids (TV), Family
        ContentCategory.Documentary -> setOf(99)
        ContentCategory.Reality -> setOf(10764)
        ContentCategory.TalkNews -> setOf(10767, 10763) // Talk, News
        ContentCategory.Soap -> setOf(10766)
        ContentCategory.Horror -> setOf(27)
        ContentCategory.Romance -> setOf(10749)
        ContentCategory.Music -> setOf(10402)
        ContentCategory.TvMovies -> setOf(10770)
    }

const val ANIMATION_GENRE = 16

/** What the filter needs to know about a title: its TMDB genres and where it was made. */
@Serializable
data class TitleTraits(
    val genres: Set<Int>,
    /** ISO 639-1 original language ("ja"). */
    val language: String? = null,
    /** ISO 3166-1 countries of origin ("JP"). */
    val countries: List<String> = emptyList(),
) {
    val isAnimation: Boolean get() = ANIMATION_GENRE in genres

    /** Anime vs. other animation can only be decided once the origin is known. */
    val hasOrigin: Boolean get() = language != null || countries.isNotEmpty()
    val isJapanese: Boolean get() = language == "ja" || "JP" in countries

    /**
     * Which region a live-action title belongs to, or null for English-language titles (and anything we can't place).
     * The language decides when it is known; the country only when there is no language, so an English-language
     * film shot in Japan isn't "Asian".
     */
    val region: ContentCategory?
        get() {
            val l = language?.lowercase()
            if (l != null && l.isNotEmpty() && l != "xx") {
                if (l in Region.eastAsianLanguages) return ContentCategory.Asian
                if (l in Region.southAsianLanguages) return ContentCategory.Indian
                return if (l == "en") null else ContentCategory.Foreign
            }
            if (countries.any { it in Region.eastAsianCountries }) return ContentCategory.Asian
            if (countries.any { it in Region.southAsianCountries }) return ContentCategory.Indian
            return null
        }
}

/** Origin tables for the region categories (ISO 639-1 languages, ISO 3166-1 countries). */
object Region {
    val eastAsianLanguages = setOf("ja", "ko", "zh", "cn", "th", "vi", "id", "ms", "tl", "fil", "km", "lo", "my", "mn")
    val southAsianLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur", "or", "as", "ne", "si")
    val eastAsianCountries = setOf("JP", "KR", "CN", "TW", "HK", "MO", "TH", "VN", "ID", "MY", "PH", "SG", "KH", "LA", "MM", "MN")
    val southAsianCountries = setOf("IN", "PK", "BD", "LK", "NP")
}

/** Add-ons list genres by name ("Reality-TV"). Maps the ones the filter cares about to their TMDB ids. */
object GenreNames {
    private val table = mapOf(
        "animation" to 16, "documentary" to 99, "family" to 10751, "kids" to 10762, "children" to 10762,
        "news" to 10763, "reality" to 10764, "realitytv" to 10764, "soap" to 10766,
        "talk" to 10767, "talkshow" to 10767, "horror" to 27, "romance" to 10749,
    )

    fun ids(names: List<String>): List<Int> = names.mapNotNull { table[it.lowercase().filter(Char::isLetter)] }
}

/** Anime add-ons (Kitsu, MAL, AniList...) say so in the catalogue type or the id prefix: no lookup needed. */
val MetaPreview.isAnimeByIdentity: Boolean
    get() = type == "anime" || listOf("kitsu:", "mal:", "anidb:", "anilist:").any { id.startsWith(it) }

/** Genres and origin as far as the source told us. null = the source said nothing about genres. */
val MetaPreview.traits: TitleTraits?
    get() {
        val g = genreIDs ?: return null
        return TitleTraits(g.toSet(), language, countries ?: emptyList())
    }

enum class ContentOutcome { Hide, Keep, Undecided }

/** Built-in Home rows (the keys `hiddenRows` holds). */
object BuiltinRows {
    const val TRENDING_MOVIES = "builtin:trend-movie"
    const val TRENDING_SHOWS = "builtin:trend-tv"
    const val BECAUSE = "builtin:because"
    const val THEMES = "builtin:themes"
}

/**
 * Stable id of an add-on catalogue. Uses the add-on's manifest id, never its URL (debrid add-on URLs hold API keys),
 * so the choice also survives re-adding an add-on with new settings.
 */
fun addonRowKey(manifestId: String, catalogType: String, catalogId: String): String = "addon:$manifestId/$catalogType/$catalogId"

/** Some region (Asian, Indian & South Asian, Other foreign-language) is switched off. */
val ContentRules.hidesRegions: Boolean get() = hiddenCategories.any { it.isRegion }

/**
 * True when a theme row built from titles in this original language could only ever show hidden titles
 * ("K-Drama Fever" with Asian titles off), so it needn't be fetched at all.
 */
fun ContentRules.hidesLanguage(language: String): Boolean {
    if (!hidesRegions) return false
    val region = TitleTraits(emptySet(), language, emptyList()).region ?: return false
    return region in hiddenCategories
}

/** Changes whenever the category choice does. Screens reload exactly when it changes. */
val ContentRules.categoryKey: String get() = hiddenCategories.map { it.raw }.sorted().joinToString(",")

/** Changes whenever an add-on catalogue is switched on or off, or added to / removed from Home. */
val ContentRules.addonRowChangeKey: String
    get() {
        val hidden = hiddenRows.filter { it.startsWith("addon:") }.sorted().joinToString(",")
        val added = addedRows.sorted().joinToString(",")
        return "$hidden|$added"
    }

fun ContentRules.isRowHidden(key: String?): Boolean = key != null && key in hiddenRows

fun ContentRules.isRowAdded(key: String): Boolean = key in addedRows

/** True when picking this genre in Explore could only ever show hidden titles. */
fun ContentRules.hidesGenre(id: Int): Boolean {
    if (id == ANIMATION_GENRE) return ContentCategory.Anime in hiddenCategories && ContentCategory.Cartoons in hiddenCategories
    return hiddenCategories.any { it.genreIDs?.contains(id) == true }
}

/**
 * TMDB `without_genres` value for the categories that are plain genres. Only an optimisation, so Explore pages
 * come back full: [outcome] stays the authority.
 */
val ContentRules.excludedGenreQuery: String?
    get() {
        val ids = hiddenCategories.flatMap { it.genreIDs ?: emptySet() }
        return if (ids.isEmpty()) null else ids.toSet().sorted().joinToString(",")
    }

/** [resolved] is what a TMDB lookup found, when one has been done. */
fun ContentRules.outcome(item: MetaPreview, resolved: TitleTraits? = null): ContentOutcome {
    if (!hasFilters) return ContentOutcome.Keep
    if (item.isAnimeByIdentity) return if (ContentCategory.Anime in hiddenCategories) ContentOutcome.Hide else ContentOutcome.Keep
    val t = resolved ?: item.traits ?: return ContentOutcome.Undecided
    return outcomeForTraits(t)
}

fun ContentRules.outcomeForTraits(t: TitleTraits): ContentOutcome {
    for (c in hiddenCategories) {
        val ids = c.genreIDs
        if (ids != null && t.genres.any { it in ids }) return ContentOutcome.Hide
    }
    // Animation is anime or a cartoon, whatever its region.
    if (t.isAnimation) {
        if (ContentCategory.Anime !in hiddenCategories && ContentCategory.Cartoons !in hiddenCategories) return ContentOutcome.Keep
        if (!t.hasOrigin) return ContentOutcome.Undecided
        val kind = if (t.isJapanese) ContentCategory.Anime else ContentCategory.Cartoons
        return if (kind in hiddenCategories) ContentOutcome.Hide else ContentOutcome.Keep
    }
    // Live-action: hidden when its region is switched off.
    if (!hidesRegions) return ContentOutcome.Keep
    if (!t.hasOrigin) return ContentOutcome.Undecided
    val r = t.region
    if (r != null && r in hiddenCategories) return ContentOutcome.Hide
    return ContentOutcome.Keep
}

/**
 * Verdict when a lookup isn't possible (no TMDB key, offline, unknown id). Animation of unknown origin goes when
 * either animation category is hidden, since it might be either. Titles we know nothing about stay.
 */
fun ContentRules.fallbackHides(item: MetaPreview): Boolean {
    val t = item.traits ?: return false
    if (!t.isAnimation) return false
    return ContentCategory.Anime in hiddenCategories || ContentCategory.Cartoons in hiddenCategories
}

package com.mediahub.pear.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * TMDB answers (spec: the nested types of `TMDBClient`). Field names are written out with @SerialName instead of
 * a naming strategy, so each one can be checked against the TMDB docs and against the Swift `convertFromSnakeCase`.
 * Every type is tolerant of unknown fields. Missing optional fields are null, like Swift's `Optional`.
 */

internal const val TMDB_IMG = "https://image.tmdb.org/t/p/"

/** TMDB's small sizes (w92 to w500), the ones saved links may still carry. */
private val TMDB_SMALL_SIZE = Regex("/t/p/w(92|154|185|300|342|500)/")

/**
 * A still or card image at w780, the size the cards need on a wide window. Links saved from earlier versions carry
 * the small sizes (w300), so the card code runs them through this too. Other URLs come back unchanged.
 */
internal fun upgradeTmdbStill(url: String?): String? = url?.replace(TMDB_SMALL_SIZE, "/t/p/w780/")

/** One result of a list, search or discover call. */
@Serializable
internal class TmdbItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null,
    /** Where the title comes from (anime vs. other animation). Movie lists only carry the language. */
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
)

@Serializable
internal class TmdbPage(val results: List<TmdbItem>)

@Serializable
internal class TmdbFind(
    @SerialName("movie_results") val movieResults: List<TmdbItem>,
    @SerialName("tv_results") val tvResults: List<TmdbItem>,
)

@Serializable
internal class TmdbExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
internal class TmdbSeasonResponse(val episodes: List<EpisodeInfo>)

@Serializable
internal class TmdbGenreList(val genres: List<Genre>)

@Serializable
internal class TmdbKeywordPage(val results: List<Keyword>) {
    @Serializable
    class Keyword(val id: Int, val name: String)
}

@Serializable
internal class TmdbVideoPage(val results: List<Video>)

@Serializable
internal class TmdbTvNetworks(val networks: List<Net>? = null) {
    @Serializable
    class Net(val name: String, @SerialName("logo_path") val logoPath: String? = null)
}

/** Posters, backdrops and logos arrive in one response, so artwork rotation costs no extra request. */
@Serializable
internal class TmdbImageSet(
    val logos: List<Logo>? = null,
    val posters: List<Art>? = null,
    val backdrops: List<Art>? = null,
) {
    @Serializable
    class Logo(
        @SerialName("file_path") val filePath: String,
        @SerialName("iso_639_1") val iso6391: String? = null,
        @SerialName("vote_average") val voteAverage: Double? = null,
        val width: Int? = null,
    )

    @Serializable
    class Art(
        @SerialName("file_path") val filePath: String,
        @SerialName("iso_639_1") val iso6391: String? = null,
        @SerialName("vote_average") val voteAverage: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
    )
}

/** Genres and origin of one title (the content filter's lookup). */
@Serializable
internal class TmdbTraitsResponse(
    val genres: List<Genre>? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
    @SerialName("production_countries") val productionCountries: List<Country>? = null,
) {
    @Serializable
    class Country(@SerialName("iso_3166_1") val iso31661: String? = null)
}

@Serializable
data class Genre(val id: Int, val name: String)

/** A YouTube trailer, teaser or clip. */
@Serializable
data class Video(
    val id: String,
    val key: String,
    val name: String,
    val site: String,
    val type: String,
    val official: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
) {
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$key/hqdefault.jpg"
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"
}

/** Broadcaster / streamer icon shown on show posters. */
@Serializable
data class NetworkBadge(val name: String, val logo: String? = null)

@Serializable
data class SeasonInfo(
    val id: Int,
    val name: String? = null,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
) {
    val posterUrl: String? get() = posterPath?.let { TMDB_IMG + "w342" + it }

    val title: String
        get() {
            val n = name
            if (!n.isNullOrEmpty()) return n
            return if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
        }
}

@Serializable
data class EpisodeInfo(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val runtime: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
) {
    val stillUrl: String? get() = stillPath?.let { TMDB_IMG + "w780" + it }
}

/** Everything the detail page shows about a title (spec: `TMDBClient.Details`). */
@Serializable
data class TmdbDetails(
    val overview: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<Named>? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val credits: Credits? = null,
    val seasons: List<SeasonInfo>? = null,
    val networks: List<Named>? = null,
    val status: String? = null,
    @SerialName("production_companies") val productionCompanies: List<Named>? = null,
    @SerialName("production_countries") val productionCountries: List<Named>? = null,
    @SerialName("spoken_languages") val spokenLanguages: List<Language>? = null,
    @SerialName("created_by") val createdBy: List<Named>? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    // Long: a blockbuster's revenue is above 2^31.
    val budget: Long? = null,
    val revenue: Long? = null,
) {
    @Serializable
    data class Named(val name: String)

    @Serializable
    data class Language(@SerialName("english_name") val englishName: String? = null)

    @Serializable
    data class Credits(val cast: List<Person>? = null, val crew: List<Person>? = null)

    @Serializable
    data class Person(val name: String, val job: String? = null)

    /** Running time in minutes: a film's, or a typical episode's. */
    val minutes: Int? get() = runtime ?: episodeRunTime?.firstOrNull()
}

enum class DiscoverSort(val label: String) {
    Popular("Popular"), TopRated("Top rated"), Newest("Newest");
}

/** A title in a themed Home row, with the genre names shown under its logo. */
data class ThemedTitle(val item: MetaPreview, val genres: List<String>) {
    val id: String get() = item.id
}

/** What TMDB returns per image, reduced to the fields artwork curation needs (spec: `ArtworkPool.Raw`). */
data class RawArtwork(val path: String, val language: String?, val width: Int, val height: Int, val votes: Double)

/**
 * Receives the posters and backdrops that come back with a title's logo request. The artwork rotation (a later
 * batch) plugs in here; until then nothing listens.
 */
fun interface ArtworkSink {
    fun ingest(id: String, posters: List<RawArtwork>, backdrops: List<RawArtwork>)

    companion object {
        val None = ArtworkSink { _, _, _ -> }
    }
}

package com.mediahub.pear.player.ui

import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.EpisodeInfo
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.TmdbDetails
import kotlin.math.abs
import kotlin.math.roundToLong

// What the player's info card shows, and how it is filled from TMDB and MDBList (Batch 4.3c). Pure: the caller
// fetches, these functions only combine. Mirrors `PlayerInfo` and `loadInfo` in PlayerInfoCard.swift / PlayerView.swift.

/** Everything the info card shows for the episode (or movie) that is playing. */
data class PlayerInfo(
    /** The series name for an episode; null for a movie. */
    val kicker: String? = null,
    /** "S2 · E6" for an episode; null for a movie. Sits on the still. */
    val episodeTag: String? = null,
    /** True while an episode's synopsis is still being fetched: placeholder lines instead of jumping in height. */
    val isLoading: Boolean = false,
    val title: String,
    /** Episode still, or the movie's backdrop. */
    val image: String? = null,
    val overview: String? = null,
    /** Shown above the synopsis when the episode has none and the series' own description stands in. */
    val overviewNote: String? = null,
    /** Already formatted: "June 12, 2025", or the year when that is all that is known. */
    val date: String? = null,
    /** Minutes. */
    val runtime: Int? = null,
    val ratings: List<MdbRating> = emptyList(),
)

object PlayerInfoRules {
    private val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** "2025-06-12" -> "June 12, 2025". Null when the text is not a calendar date. */
    fun longDate(iso: String?): String? {
        val parts = iso?.trim()?.split("-") ?: return null
        if (parts.size != 3 || parts[0].length != 4) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..daysIn(year, month)) return null
        return "${months[month - 1]} $day, $year"
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** 105 -> "1h 45m", 120 -> "2h", 45 -> "45 min". Null when there is no runtime. */
    fun runtimeText(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    /** What is known without a network call: the request and its catalog entry (`basicInfo` in PlayerView.swift). */
    fun basic(request: PlayRequest): PlayerInfo {
        val item = request.item
        val season = request.season
        val episode = request.episode
        if (season != null && episode != null) {
            return PlayerInfo(
                kicker = item.name,
                episodeTag = "S$season · E$episode",
                isLoading = true,
                title = request.episodeTitle?.takeIf { it.isNotBlank() } ?: "Episode $episode",
                image = request.thumb ?: item.backdropUrl,
            )
        }
        return PlayerInfo(
            title = item.name,
            image = request.thumb ?: item.backdropUrl,
            overview = item.description,
            date = item.releaseInfo,
        )
    }

    /**
     * An episode's card once TMDB has answered: its own still, synopsis, air date, runtime and TMDB rating. A new
     * episode often has no synopsis yet, so the series' description stands in and says so. [episode] is null when
     * TMDB had nothing for it.
     */
    fun episodeCard(request: PlayRequest, episode: EpisodeInfo?): PlayerInfo {
        var out = basic(request)
        if (episode != null) {
            out = out.copy(
                title = episode.name?.takeIf { it.isNotBlank() } ?: out.title,
                image = episode.stillUrl ?: out.image,
                overview = episode.overview,
                date = longDate(episode.airDate),
                runtime = episode.runtime,
                ratings = listOfNotNull(tmdbRating(episode.voteAverage)),
            )
        }
        val series = request.item.description
        if (out.overview.isNullOrBlank() && !series.isNullOrBlank()) {
            out = out.copy(overview = series, overviewNote = "About the series")
        }
        return out.copy(isLoading = false)
    }

    /**
     * A movie's card: release date, runtime and synopsis from TMDB, and the ratings from MDBList with the TMDB vote
     * and the catalog's own score filling in what is missing. Each source keeps its first entry only.
     */
    fun movieCard(request: PlayRequest, details: TmdbDetails?, mdb: List<MdbRating>): PlayerInfo {
        val item = request.item
        val ratings = mdb.toMutableList()
        fun add(label: String, value: Double?) {
            if (value == null || value <= 0.0 || ratings.any { it.label == label }) return
            ratings.add(MdbRating(label, oneDecimal(value), value))
        }
        add("TMDB", details?.voteAverage)
        add(if (item.id.startsWith("tt")) "IMDb" else "TMDB", item.rating)
        return PlayerInfo(
            title = item.name,
            image = request.thumb ?: item.backdropUrl,
            overview = details?.overview?.takeIf { it.isNotBlank() } ?: item.description,
            date = longDate(details?.releaseDate) ?: item.releaseInfo,
            runtime = details?.minutes,
            ratings = ratings,
        )
    }

    private fun tmdbRating(vote: Double?): MdbRating? =
        if (vote == null || vote <= 0.0) null else MdbRating("TMDB", oneDecimal(vote), vote)

    /** 7.46 -> "7.5". For positive scores. */
    internal fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToLong()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }
}

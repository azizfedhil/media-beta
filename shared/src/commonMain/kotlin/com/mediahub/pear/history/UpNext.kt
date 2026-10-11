package com.mediahub.pear.history

import com.mediahub.pear.meta.TmdbClient
import com.mediahub.pear.meta.TvdbClient
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import com.mediahub.pear.todayIso

/** One episode of a season (spec: `EpisodeItem`). [id] is the episode number. */
data class EpisodeItem(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val image: String? = null,
    val rating: Double? = null,
    val runtime: Int? = null,
    /** `yyyy-MM-dd`, when known. */
    val airDate: String? = null,
    /** TMDB vote count behind [rating], when known. */
    val votes: Int? = null,
    /** Best rated episode of its season (set by [markTopRated]). */
    val isTopRated: Boolean = false,
) {
    /** The air date is known and still ahead of [today]. Unknown dates count as aired, so a source without dates never looks unreleased. */
    fun isUpcoming(today: String = todayIso()): Boolean = !airDate.isNullOrEmpty() && airDate > today

    companion object {
        /**
         * Flags the season's best rated episode(s). Only aired episodes with a real rating count (and, when TMDB says
         * how many votes stand behind it, at least 5), at least three must be rated, and the top score has to beat the
         * lowest, so a season of identical scores doesn't crown everything.
         */
        fun markTopRated(list: List<EpisodeItem>, today: String = todayIso()): List<EpisodeItem> {
            val cleared = list.map { it.copy(isTopRated = false) }
            val rated = cleared.filter { !it.isUpcoming(today) && (it.rating ?: 0.0) > 0 && (it.votes ?: 5) >= 5 }
            if (rated.size < 3) return cleared
            val ratings = rated.mapNotNull { it.rating }
            val top = ratings.maxOrNull() ?: return cleared
            val low = ratings.minOrNull() ?: return cleared
            if (top <= low) return cleared
            return cleared.map { if (it.rating == top && !it.isUpcoming(today)) it.copy(isTopRated = true) else it }
        }
    }
}

/** Loads a season's episodes: TMDB first (stills, overviews, ratings); TheTVDB fills missing thumbnails or stands in. */
class EpisodeLoader(
    private val tmdb: TmdbClient,
    private val tvdb: TvdbClient,
    private val today: () -> String = ::todayIso,
) {
    /** [imdb] gives the title's IMDb id when TVDB is needed. */
    suspend fun load(itemId: String, type: String, season: Int, imdb: suspend () -> String?): List<EpisodeItem> {
        var list = tmdb.episodes(itemId, type, season).map {
            EpisodeItem(
                id = it.episodeNumber, name = it.name ?: "Episode ${it.episodeNumber}", overview = it.overview,
                image = it.stillUrl, rating = it.voteAverage, runtime = it.runtime, airDate = it.airDate, votes = it.voteCount,
            )
        }
        val needsArt = list.any { it.image == null }
        if ((list.isEmpty() || needsArt) && tvdb.hasKey) {
            val id = imdb()
            if (id != null) {
                val tv = attempt { tvdb.episodes(id, season) } ?: emptyList()
                list = if (list.isEmpty()) {
                    tv.mapNotNull { e ->
                        e.number?.let {
                            EpisodeItem(id = it, name = e.name ?: "Episode $it", overview = e.overview, image = e.imageUrl, runtime = e.runtime, airDate = e.aired)
                        }
                    }
                } else {
                    list.map { ep -> if (ep.image == null) ep.copy(image = tv.firstOrNull { it.number == ep.id }?.imageUrl) else ep }
                }
            }
        }
        return EpisodeItem.markTopRated(list, today())
    }
}

/** The next episode of a show you've been watching (spec: `UpNextItem`). */
data class UpNextItem(
    val item: MetaPreview,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String?,
    val runtime: Int?,
    /** When the finished episode was watched; orders this card among the Continue Watching cards. */
    val updated: Long,
) {
    val id: String get() = "${item.id}:$season:$episode"
}

object UpNext {
    /**
     * The episode after [season]/[episode]: the next one in the season, else episode 1 of the next season. null when
     * the show has nothing further that has aired. [load] gives a season's episodes (`EpisodeLoader.load`).
     */
    suspend fun next(
        season: Int,
        episode: Int,
        load: suspend (Int) -> List<EpisodeItem>,
        today: String = todayIso(),
    ): Pair<Int, EpisodeItem>? {
        fun aired(e: EpisodeItem) = e.airDate.isNullOrEmpty() || e.airDate <= today
        val current = load(season)
        if (current.isEmpty()) {
            // No metadata source answered: assume the following episode exists.
            return season to EpisodeItem(episode + 1, "Episode ${episode + 1}")
        }
        current.filter { it.id > episode }.minByOrNull { it.id }?.let { return if (aired(it)) season to it else null }
        if (season < 1) return null
        val first = load(season + 1).filter { it.id >= 1 }.minByOrNull { it.id }
        if (first != null && aired(first)) return (season + 1) to first
        return null
    }

    suspend fun resolve(entry: HistoryEntry, load: suspend (Int) -> List<EpisodeItem>, today: String = todayIso()): UpNextItem? {
        val se = entry.watchedThrough ?: entry.seasonEpisode ?: return null
        val n = next(se.season, se.episode, load, today) ?: return null
        return UpNextItem(entry.item, n.first, n.second.id, n.second.name, n.second.image ?: entry.item.backdropUrl, n.second.runtime, entry.updated)
    }
}

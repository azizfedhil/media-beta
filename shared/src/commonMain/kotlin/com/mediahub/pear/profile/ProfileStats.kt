package com.mediahub.pear.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.TitleTraits
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LibraryEntry
import com.mediahub.pear.history.LibraryStatus
import com.mediahub.pear.localParts
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.TmdbDetails

enum class StatKind(val label: String) {
    All("All"),
    Shows("Shows"),
    Movies("Movies"),
}

enum class StatTitleStatus {
    Watching,
    Completed,
    Planned,
}

data class StatTitle(
    val id: String,
    val item: MetaPreview,
    val status: StatTitleStatus,
    val episodes: Int,
    val minutes: Double,
    val genres: List<String>,
    val year: Int?,
    val isSeries: Boolean,
)

data class Share(val id: String, val percent: Double)
data class DayPart(val id: String, val symbol: String, val range: String, val percent: Double)
data class Era(val buckets: List<Share>, val medianYear: Int?, val recentShare: Double)

data class ProfileStats(
    val titles: List<StatTitle> = emptyList(),
    val hourSeconds: List<Double> = List(24) { 0.0 },
    val trackedSeconds: Double = 0.0,
    val trackedDailySeconds: Double? = null,
    val earliestMillis: Long? = null,
) {
    fun count(status: StatTitleStatus, kind: StatKind = StatKind.All): Int =
        titles.count { it.status == status && matchesKind(it, kind) }

    val episodesSeen: Int get() = titles.sumOf { it.episodes }

    fun minutes(kind: StatKind): Double =
        titles.filter { matchesKind(it, kind) }.sumOf { it.minutes }

    val totalMinutes: Double get() = maxOf(minutes(StatKind.All), trackedSeconds / 60.0)

    val averageDaily: Pair<Double, Boolean>?
        get() {
            if (trackedDailySeconds != null && trackedDailySeconds > 0) {
                return (trackedDailySeconds / 60.0) to true
            }
            val e = earliestMillis ?: return null
            val days = maxOf((epochMillis() - e) / 86_400_000.0, 7.0)
            val m = minutes(StatKind.All)
            return if (m > 0) (m / days) to false else null
        }

    private val counted: List<StatTitle> get() = titles.filter { it.status != StatTitleStatus.Planned && it.minutes > 0 }

    fun genreShares(kind: StatKind, top: Int = 6): List<Share> {
        val weights = HashMap<String, Double>()
        for (t in counted) {
            if (!matchesKind(t, kind)) continue
            val g = t.genres
            if (g.isEmpty()) continue
            val part = t.minutes / g.size.toDouble()
            for (n in g) weights[n] = (weights[n] ?: 0.0) + part
        }
        val total = weights.values.sum()
        if (total <= 0.0) return emptyList()
        val sorted = weights.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        val out = sorted.take(top).map { Share(it.key, it.value / total * 100.0) }.toMutableList()
        val rest = sorted.drop(top).sumOf { it.value }
        if (rest > 0.0) out.add(Share("Other", rest / total * 100.0))
        return out
    }

    fun era(kind: StatKind): Era? {
        val rows = counted.filter { matchesKind(it, kind) }.mapNotNull { t -> t.year?.let { it to t.minutes } }
        val total = rows.sumOf { it.second }
        if (total <= 0.0) return null
        val labels = listOf("Before 1990", "1990s", "2000s", "2010s", "2020s")
        val buckets = DoubleArray(labels.size)
        for ((y, w) in rows) {
            val idx = when {
                y < 1990 -> 0
                else -> minOf((y - 1990) / 10 + 1, 4)
            }
            buckets[idx] += w
        }
        var acc = 0.0
        var median: Int? = null
        for ((y, w) in rows.sortedBy { it.first }) {
            acc += w
            if (acc >= total / 2.0) {
                median = y
                break
            }
        }
        val currentYear = localParts(epochMillis()).year
        val recentShare = rows.filter { it.first >= currentYear - 5 }.sumOf { it.second } / total
        return Era(
            buckets = labels.indices.map { Share(labels[it], buckets[it] / total * 100.0) },
            medianYear = median,
            recentShare = recentShare,
        )
    }

    val peakHour: Int?
        get() {
            val max = hourSeconds.maxOrNull() ?: 0.0
            return if (max > 0.0) hourSeconds.indexOf(max) else null
        }

    val dayParts: List<DayPart>
        get() {
            val total = hourSeconds.sum()
            if (total <= 0.0) return emptyList()
            fun pct(range: IntRange): Double = range.sumOf { hourSeconds.getOrElse(it) { 0.0 } } / total * 100.0
            return listOf(
                DayPart("Morning", "sunrise.fill", "5 AM – 12 PM", pct(5..11)),
                DayPart("Afternoon", "sun.max.fill", "12 – 5 PM", pct(12..16)),
                DayPart("Evening", "sunset.fill", "5 – 10 PM", pct(17..21)),
                DayPart("Night", "moon.stars.fill", "10 PM – 5 AM", (pct(22..23) + pct(0..4))),
            )
        }

    private fun matchesKind(title: StatTitle, kind: StatKind): Boolean = when (kind) {
        StatKind.All -> true
        StatKind.Shows -> title.isSeries
        StatKind.Movies -> !title.isSeries
    }
}

class ProfileStatsModel(private val data: AppData) {
    var stats by mutableStateOf(ProfileStats())
        private set

    /** TMDB's genre names by id. Movie and TV lists are merged: the shared genres have the same name in both. */
    private suspend fun genreNameMap(): Map<Int, String> {
        if (!data.meta.tmdb.hasKey) return emptyMap()
        return (data.meta.tmdb.genres("movie") + data.meta.tmdb.genres("tv")).associate { it.id to it.name }
    }

    /** A title's genre names: from its TMDB ids, or from its details when the source listed no ids. */
    private suspend fun genreNames(item: MetaPreview, names: Map<Int, String>): List<String> {
        val ids = item.genreIDs.orEmpty()
        if (ids.isNotEmpty()) return ids.mapNotNull { names[it] }
        if (!data.meta.tmdb.hasKey) return emptyList()
        return data.meta.tmdb.cachedDetails(item.id, item.type)?.genres?.map { it.name }.orEmpty()
    }
    var loading by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun refresh(includeSimkl: Boolean = true) {
        loading = true
        try {
            val historyEntries = data.history.allEntries
            val localEntries = data.library.entries
            val simklRows = if (includeSimkl) data.simkl.library else emptyList()
            // Genre names, not TMDB's numbers: the favourite genres list reads them from here.
            val names = genreNameMap()

            val builtTitles = mutableListOf<StatTitle>()
            val processedIds = HashSet<String>()

            // 1. Process History Entries
            for (h in historyEntries) {
                if (!processedIds.add(h.id)) continue
                val isSeries = h.item.type == "series"
                val status = when {
                    h.isTitleWatched -> StatTitleStatus.Completed
                    h.isInProgress -> StatTitleStatus.Watching
                    else -> null
                } ?: continue

                val epCount = if (isSeries) (h.watchedThrough?.episode ?: h.seasonEpisode?.episode ?: 1) else 0
                val rt = if (isSeries) 42.0 else maxOf(h.duration / 60.0, 105.0)
                val mins = if (isSeries) epCount * rt else if (h.isTitleWatched) rt else h.position / 60.0

                builtTitles.add(
                    StatTitle(
                        id = h.id,
                        item = h.item,
                        status = status,
                        episodes = epCount,
                        minutes = mins,
                        genres = genreNames(h.item, names),
                        year = h.item.year,
                        isSeries = isSeries,
                    ),
                )
            }

            // 2. Process Local Library Entries
            for (l in localEntries) {
                if (processedIds.contains(l.id)) continue
                processedIds.add(l.id)
                val isSeries = l.item.type == "series"
                val status = when (l.status) {
                    LibraryStatus.Watched -> StatTitleStatus.Completed
                    LibraryStatus.PlanToWatch -> StatTitleStatus.Planned
                }
                val rt = if (isSeries) 42.0 else 105.0
                builtTitles.add(
                    StatTitle(
                        id = l.id,
                        item = l.item,
                        status = status,
                        episodes = if (status == StatTitleStatus.Completed && isSeries) 10 else 0,
                        minutes = if (status == StatTitleStatus.Completed) rt else 0.0,
                        genres = genreNames(l.item, names),
                        year = l.item.year,
                        isSeries = isSeries,
                    ),
                )
            }

            val earliest = (historyEntries.map { it.updated } + localEntries.map { it.added }).minOrNull()
            val watchLog = data.watchLog

            stats = ProfileStats(
                titles = builtTitles,
                hourSeconds = watchLog.hourTotals,
                trackedSeconds = watchLog.totalSeconds,
                trackedDailySeconds = watchLog.averageDailySeconds(),
                earliestMillis = earliest,
            )
        } finally {
            loading = false
            loaded = true
        }
    }
}
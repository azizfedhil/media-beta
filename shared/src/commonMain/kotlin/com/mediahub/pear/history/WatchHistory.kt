package com.mediahub.pear.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.AppleDate
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlin.math.ceil

/** Season and episode, ordered like Swift's `(season, episode)` tuples. */
data class SE(val season: Int, val episode: Int) : Comparable<SE> {
    override fun compareTo(other: SE): Int = if (season != other.season) season.compareTo(other.season) else episode.compareTo(other.episode)
}

/**
 * Progress on one title (spec: `WatchHistory.Entry`). Field names and the date format are the Swift ones, so a backed-up
 * history reads in both apps. Playback progress and watched state are kept apart: playing an episode partway never clears
 * the watched marker.
 */
@Serializable
data class HistoryEntry(
    val item: MetaPreview,
    /** Stream id including the episode ("2:5"), so resume only applies to the same episode. */
    val key: String,
    val position: Double,
    val duration: Double,
    /** Epoch milliseconds in memory; Swift's `Date` number in the JSON. */
    @Serializable(with = AppleDate::class) val updated: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** Episode still, or the movie backdrop. */
    val thumb: String? = null,
    /** Series: everything up to wS·wE is watched (0·0 = explicitly cleared). */
    val wS: Int? = null,
    val wE: Int? = null,
    /** Movies: explicitly watched / unwatched. */
    val done: Boolean? = null,
    /** The title's other id (tt... vs tmdb:...). */
    val alias: String? = null,
    /** Up Next was dismissed while the watched marker was at hS·hE; it comes back once a later episode is finished. */
    val hS: Int? = null,
    val hE: Int? = null,
) {
    val id: String get() = item.id
    fun matches(x: String): Boolean = item.id == x || alias == x

    /** Series watched marker. Older saves without one fall back to a finished last episode. */
    val watchedThrough: SE?
        get() {
            val s = wS
            val e = wE
            if (s != null && e != null) return if (s == 0) null else SE(s, e)
            return if (isFinished && item.type == "series") seasonEpisode else null
        }

    /** Watched as a whole: a series with a marker, a movie that was finished or marked. */
    val isTitleWatched: Boolean get() = if (item.type == "series") watchedThrough != null else (done ?: isFinished)
    val isInProgress: Boolean get() = position > 30 && !isFinished
    val progress: Double get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0

    /** Watched far enough that the next episode is the natural thing to offer. */
    val isFinished: Boolean get() = duration > 0 && position >= duration * 0.92

    /** From the stored fields or (older entries) parsed from the "2:5" key. */
    val seasonEpisode: SE?
        get() {
            val s = season
            val e = episode
            if (s != null && e != null) return SE(s, e)
            val p = key.split(':').mapNotNull { it.toIntOrNull() }
            return if (p.size == 2) SE(p[0], p[1]) else null
        }

    /** Eligible for "Up Next": a finished series episode whose numbers we can read. */
    val isUpNextCandidate: Boolean
        get() {
            val w = watchedThrough
            if (item.type != "series" || w == null || isInProgress) return false
            if (hS != null && hE != null && SE(hS, hE) == w) return false
            return true
        }
}

private val entriesSerializer = ListSerializer(HistoryEntry.serializer())

/** Reads a saved list, skipping an entry that can't be read instead of losing the whole history. */
internal fun readEntries(text: String?): List<HistoryEntry> {
    if (text == null) return emptyList()
    val arr = attempt { PearJson.parseToJsonElement(text) as? JsonArray } ?: return emptyList()
    return arr.mapNotNull { el -> attempt { PearJson.decodeFromJsonElement(HistoryEntry.serializer(), el) } }
}

/**
 * Local progress of the active profile (spec: `WatchHistory`). Powers Continue Watching, resume and Up Next.
 * Saved under `watch.history` / `watch.archive` (or `.<profileId>`). Like [PrefStore], use it from the UI thread.
 */
class WatchHistory(
    private val prefs: PrefStore,
    initialProfile: String = ProfileKeys.DEFAULT_ID,
    private val now: () -> Long = ::epochMillis,
) {
    private var profileID = initialProfile

    var entries: List<HistoryEntry> by mutableStateOf(readEntries(prefs.jsonText(storeKey(initialProfile))))
        private set

    /**
     * Everything that dropped off [entries] (the list is capped at 30 for Continue Watching) or was removed from it.
     * Never shown; the profile statistics read it so no watched title is ever forgotten.
     */
    var archive: List<HistoryEntry> by mutableStateOf(readEntries(prefs.jsonText(archiveKey(initialProfile))))
        private set

    /** Hint for [isWatched] when no id is given: the show the caller most recently resolved ids for. */
    var lastWatchedSeriesId: String? = null

    private fun storeKey(profile: String) = ProfileKeys.scoped(PrefKeys.WATCH_HISTORY, profile)
    private fun archiveKey(profile: String) = ProfileKeys.scoped(PrefKeys.WATCH_ARCHIVE, profile)

    /** Re-reads the profile's history from storage (after a settings import, or a switch of profile). */
    fun reload(profile: String = profileID) {
        profileID = profile
        entries = readEntries(prefs.jsonText(storeKey(profileID)))
        archive = readEntries(prefs.jsonText(archiveKey(profileID)))
    }

    /** Switches to another profile's history. Does nothing when it is already loaded. */
    fun load(profile: String) {
        if (profile != profileID) reload(profile)
    }

    /** Live entries plus archived ones (a live entry wins when a title is in both). */
    val allEntries: List<HistoryEntry>
        get() {
            val live = entries.map { it.id }.toSet()
            return entries + archive.filter { it.id !in live }
        }

    val lastWatched: MetaPreview? get() = entries.firstOrNull()?.item
    val continueEntries: List<HistoryEntry> get() = entries.filter { it.isInProgress }
    val continueWatching: List<MetaPreview> get() = continueEntries.map { it.item }

    /** Ids of the shows whose last episode is done: cheap to diff, so screens use them as keys. */
    val finishedSeries: Set<String> get() = entries.filter { it.isUpNextCandidate }.map { it.id }.toSet()

    /** Shows whose last episode is done: candidates for "Up Next". */
    val finishedEntries: List<HistoryEntry> get() = entries.filter { it.isUpNextCandidate }

    fun entry(id: String): HistoryEntry? = entries.firstOrNull { it.matches(id) } ?: archive.firstOrNull { it.matches(id) }

    private fun index(id: String): Int? = entries.indexOfFirst { it.matches(id) }.takeIf { it >= 0 }

    private fun replace(i: Int, e: HistoryEntry) {
        entries = entries.toMutableList().also { it[i] = e }
    }

    // MARK: Changes

    /** Records that two ids are the same title (tt... and tmdb:...), merging any split entries. */
    fun link(a: String, b: String) {
        if (a == b) return
        val i = index(a)
        val j = index(b)
        if (i == null || j == null || i == j) {
            if (i != null && entries[i].alias == null && entries[i].item.id != b) {
                replace(i, entries[i].copy(alias = b)); persist()
            } else if (j != null && entries[j].alias == null && entries[j].item.id != a) {
                replace(j, entries[j].copy(alias = a)); persist()
            }
            return
        }
        val keepAt = minOf(i, j)
        val otherAt = maxOf(i, j)
        var keep = entries[keepAt]
        val other = entries[otherAt]
        val ow = other.watchedThrough
        if (ow != null && ow >= (keep.watchedThrough ?: SE(0, 0))) keep = keep.copy(wS = ow.season, wE = ow.episode)
        if (keep.done != true) keep = keep.copy(done = other.done ?: keep.done)
        keep = keep.copy(alias = if (keep.item.id == a) b else a)
        val list = entries.toMutableList()
        list[keepAt] = keep
        list.removeAt(otherAt)
        entries = list
        persist()
    }

    /**
     * New progress record for [item]. Watched state is carried over from the old entry and only ever raised by
     * finishing an episode here.
     */
    private fun build(
        item: MetaPreview, key: String, position: Double, duration: Double,
        season: Int?, episode: Int?, episodeTitle: String?, thumb: String?,
    ): HistoryEntry {
        val old = entry(item.id)
        var e = HistoryEntry(item, key, position, duration, now(), season, episode, episodeTitle, thumb)
        e = e.copy(alias = old?.alias, done = old?.done, hS = old?.hS, hE = old?.hE)
        var wt = old?.watchedThrough
        if (e.isFinished) {
            if (item.type == "series") {
                val se = e.seasonEpisode
                if (se != null && se > (wt ?: SE(0, 0))) wt = se
            } else {
                e = e.copy(done = true)
            }
        }
        if (wt != null) e = e.copy(wS = wt.season, wE = wt.episode)
        else if (old?.wS == 0) e = e.copy(wS = 0, wE = 0)
        return e
    }

    fun update(
        item: MetaPreview, key: String, position: Double, duration: Double,
        season: Int? = null, episode: Int? = null, episodeTitle: String? = null, thumb: String? = null,
    ) {
        val e = build(item, key, position, duration, season, episode, episodeTitle, thumb)
        val list = entries.filter { !it.matches(item.id) && !it.matches(e.alias ?: item.id) }.toMutableList()
        list.add(0, e)
        if (archive.any { it.matches(item.id) }) {
            archive = archive.filter { !it.matches(item.id) }
            persistArchive()
        }
        trim(list)
        entries = list
        persist()
    }

    /** Keeps the list near 30, but never pushes out a show that is in progress or has a watched marker. */
    private fun trim(list: MutableList<HistoryEntry>) {
        while (list.size > 30) {
            val i = list.indexOfLast { !it.isInProgress && !(it.item.type == "series" && it.watchedThrough != null) }
            if (i < 0) break
            stash(list.removeAt(i))
        }
        if (list.size > 100) {
            for (d in list.drop(100)) stash(d)
            while (list.size > 100) list.removeAt(list.size - 1)
        }
    }

    private fun stash(e: HistoryEntry) {
        archive = archive.filter { it.id != e.id } + e
        persistArchive()
    }

    /**
     * Periodic progress save while a video plays. It goes straight to storage and leaves the observed lists alone, so
     * screens don't redraw behind the player every few seconds. The save when the player closes ([update]) brings the
     * in-memory list up to date.
     */
    fun checkpoint(
        item: MetaPreview, key: String, position: Double, duration: Double,
        season: Int? = null, episode: Int? = null, episodeTitle: String? = null, thumb: String? = null,
    ) {
        if (entries.none { it.matches(item.id) }) {
            update(item, key, position, duration, season, episode, episodeTitle, thumb)
            return
        }
        val e = build(item, key, position, duration, season, episode, episodeTitle, thumb)
        val list = entries.filter { !it.matches(item.id) }.toMutableList()
        list.add(0, e)
        prefs.setJsonText(storeKey(profileID), PearJson.encodeToString(entriesSerializer, list))
    }

    /**
     * Hides a title from Continue Watching. Its progress still counts in the statistics, and a show with a watched
     * marker stays (reset to no progress) so it keeps its marks and Up Next.
     */
    fun remove(id: String) {
        val i = index(id) ?: return
        if (entries[i].item.type == "series" && entries[i].watchedThrough != null) {
            replace(i, entries[i].copy(position = 0.0))
        } else {
            val list = entries.toMutableList()
            stash(list.removeAt(i))
            entries = list
        }
        persist()
    }

    /** Hides the Up Next card for a show until another episode is finished. The watched marker is untouched. */
    fun dismissUpNext(id: String) {
        val i = index(id) ?: return
        val w = entries[i].watchedThrough ?: return
        replace(i, entries[i].copy(hS = w.season, hE = w.episode))
        persist()
    }

    // MARK: Mark as watched (long-press menus on posters, seasons and episodes)

    /** Marks a title watched. Series go through [markThrough]; a movie becomes a finished entry. */
    fun markWatched(
        item: MetaPreview, key: String = "watched", season: Int? = null, episode: Int? = null,
        duration: Double? = null, episodeTitle: String? = null, thumb: String? = null,
    ) {
        val old = entry(item.id)
        if (item.type == "series") {
            val s = season ?: old?.seasonEpisode?.season ?: 1
            val e = episode ?: old?.seasonEpisode?.episode ?: 1
            markThrough(e, s, item, duration, episodeTitle, thumb)
            return
        }
        val d = maxOf(duration ?: 0.0, if (old != null && old.isFinished) old.duration else 0.0, 60.0)
        update(item, key, d, d, null, null, episodeTitle ?: old?.episodeTitle, thumb ?: old?.thumb)
    }

    /** Clears the watched state without dropping the rest of the record. */
    fun unmarkWatched(id: String) {
        val i = index(id) ?: return
        var e = entries[i].copy(wS = 0, wE = 0, done = false, hS = null, hE = null)
        if (e.isFinished) e = e.copy(position = 0.0)
        replace(i, e)
        persist()
    }

    /** Everything up to and including [season]/[episode] counts as watched afterwards. */
    fun isWatched(season: Int, episode: Int, id: String? = null): Boolean {
        val target = id ?: lastWatchedSeriesId ?: ""
        val w = entry(target)?.watchedThrough ?: return false
        return w >= SE(season, episode)
    }

    private fun setMarker(i: Int, m: SE?) {
        replace(i, entries[i].copy(wS = m?.season ?: 0, wE = m?.episode ?: 0))
        persist()
    }

    /** Marks Sx·Ey watched, which covers every earlier episode too. Never lowers an existing marker. */
    fun markThrough(
        episode: Int, season: Int, item: MetaPreview, duration: Double?,
        episodeTitle: String? = null, thumb: String? = null,
    ) {
        val old = entry(item.id)
        val w = old?.watchedThrough
        if (w != null && w >= SE(season, episode)) return
        if (old != null && old.isInProgress) {
            val i = index(old.id)
            if (i != null) {
                setMarker(i, SE(season, episode)) // keep the episode being watched as the progress record
                return
            }
        }
        val d = maxOf(duration ?: 0.0, old?.duration ?: 0.0, 60.0)
        update(item, "$season:$episode", d, d, season, episode, episodeTitle, thumb)
    }

    /**
     * Pulls the watched marker back to just before Sx·Ey. Episode 1 of a later season leaves "season s-1 done" as
     * (s, 0); S1E1 clears the marker. The progress record is left alone.
     */
    fun unmarkThrough(episode: Int, season: Int, item: MetaPreview) {
        val i = index(item.id) ?: return
        val w = entries[i].watchedThrough ?: return
        if (w < SE(season, episode)) return
        when {
            episode > 1 -> setMarker(i, SE(season, episode - 1))
            season > 1 -> setMarker(i, SE(season, 0))
            else -> setMarker(i, null)
        }
    }

    private fun persist() {
        prefs.setJsonText(storeKey(profileID), PearJson.encodeToString(entriesSerializer, entries))
    }

    private fun persistArchive() {
        prefs.setJsonText(archiveKey(profileID), PearJson.encodeToString(entriesSerializer, archive))
    }
}

/** Time formatting shared by the player and the Continue Watching cards. */
object Fmt {
    /** 83 -> "1:23", 3725 -> "1:02:05" */
    fun clock(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0:00"
        val t = seconds.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** 2460 -> "41 min left", 4500 -> "1h 15m left" */
    fun remaining(seconds: Double): String {
        val m = maxOf(ceil(maxOf(seconds, 0.0) / 60).toInt(), 1)
        if (m >= 60) return if (m % 60 == 0) "${m / 60}h left" else "${m / 60}h ${m % 60}m left"
        return "$m min left"
    }
}

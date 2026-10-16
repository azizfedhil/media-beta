package com.mediahub.pear.simkl

import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.SE
import com.mediahub.pear.history.WatchHistory
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Brings progress made in other apps into the local history: the watched marker of a show, a film marked watched, and what
 * was paused part way through. Only what Simkl says is newer is written, and nothing local is ever lowered or removed, so this
 * can only add. It runs only while Simkl is connected and is the profile's library (`usesSimkl`).
 *
 * Simkl is asked first whether anything changed (`/sync/activities`); when nothing did, the pull stops after that one small
 * request. Every field of every reply is optional, and a reply that cannot be read is treated as nothing to import.
 */
class SimklHistorySync(
    private val simkl: SimklStore,
    private val history: WatchHistory,
    private val usesSimkl: () -> Boolean,
    /** Runtime in minutes for a title, from the metadata cache when it is there. The fallback is 45 for a show and 120 for a film. */
    private val minutesOf: suspend (MetaPreview) -> Int?,
    private val now: () -> Long = ::epochMillis,
) {
    private val lock = Mutex()
    private var lastPull: Long? = null

    /** The activity stamp per profile, in memory only: losing it costs one extra pull. */
    private val stamps = HashMap<String, String>()

    /**
     * Pulls once. [force] skips the three-minute throttle and the activity check. Never throws: a failure is logged and the
     * next pull tries again. Call it from the UI thread (the history it writes is UI-thread only).
     */
    suspend fun pull(profile: String, force: Boolean = false) {
        if (!simkl.isConnected || !usesSimkl()) return
        if (!lock.tryLock()) return
        try {
            val last = lastPull
            if (!force && last != null && now() - last < THROTTLE_MILLIS) return
            lastPull = now()
            simkl.flushOutbox() // our own unsent progress goes first, so the pull cannot overwrite it
            val activity = simkl.getJson("/sync/activities").jObject() ?: return
            val stamp = listOf(activity["all"], activity["tv_shows"], activity["movies"])
                .joinToString("|") { it?.toString().orEmpty() }
            if (!force && stamps[profile] == stamp) return
            val playback = simkl.getJson("/sync/playback/")
            val shows = simkl.getJson("/sync/all-items/shows/", mapOf("extended" to "full", "episode_watched_at" to "yes"))
            val movies = simkl.getJson("/sync/all-items/movies/")
            merge(SimklView.parse(playback, shows, movies))
            stamps[profile] = stamp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PearLog.w("SimklSync", "pull failed", e)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun merge(titles: List<SimklView>) {
        for (t in titles) {
            val local = history.entry(t.id)
            val item = local?.item ?: t.toMetaPreview()
            val seconds = (minutesOf(item) ?: if (t.type == "series") 45 else 120) * 60.0

            // Watched: only ever raise the marker, or mark a film that is not finished here yet.
            val watched = t.watched
            if (t.type == "series" && watched != null && watched > (local?.watchedThrough ?: SE(0, 0))) {
                history.markThrough(watched.episode, watched.season, item, seconds)
            } else if (t.type == "movie" && t.completed && local?.isFinished != true) {
                history.markWatched(item, duration = seconds)
            }

            // Paused part way: only when Simkl's pause is newer than anything saved here.
            val playing = t.playing ?: continue
            val pausedAt = playing.pausedAt ?: continue
            if (playing.percent <= 0.0 || (local != null && pausedAt <= local.updated)) continue
            val se = playing.se
            history.update(
                item = item,
                key = if (se != null) "${se.season}:${se.episode}" else "movie",
                position = playing.percent / 100.0 * seconds,
                duration = seconds,
                season = se?.season,
                episode = se?.episode,
            )
        }
    }

    companion object {
        const val THROTTLE_MILLIS = 180_000L
    }
}

/** What Simkl says about one title, after reading the three replies. */
internal class SimklView(val id: String, val type: String, val name: String, val poster: String?, val year: String?) {
    var watched: SE? = null
    var completed: Boolean = false
    var playing: Playing? = null

    fun toMetaPreview() = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster?.let { "https://simkl.in/posters/${it}_m.jpg" },
        releaseInfo = year,
    )

    class Playing(val se: SE?, val percent: Double, val pausedAt: Long?)

    companion object {
        /** Reads the replies. Anything missing or odd is skipped. */
        fun parse(playback: JsonElement, shows: JsonElement, movies: JsonElement): List<SimklView> {
            val byId = LinkedHashMap<String, SimklView>()

            fun titleFor(media: JsonObject, type: String): SimklView? {
                val id = idOf(media["ids"].jObject()) ?: return null
                return byId.getOrPut(id) {
                    SimklView(id, type, media["title"].jStr().orEmpty(), media["poster"].jStr(), media["year"].jInt()?.toString())
                }
            }

            for (el in items(playback, "")) {
                val o = el.jObject() ?: continue
                val percent = o["progress"].jDouble() ?: continue
                val movie = o["movie"].jObject()
                val show = o["show"].jObject()
                val view = if (movie != null) titleFor(movie, "movie") else if (show != null) titleFor(show, "series") else null
                if (view == null) continue
                val se = o["episode"].jObject()?.let { ep -> SE(ep["season"].jInt() ?: 0, ep["number"].jInt() ?: 0) }
                    ?.takeIf { it.season > 0 && it.episode > 0 }
                view.playing = Playing(se, percent, parseIsoMillis(o["paused_at"].jStr()))
            }

            for (el in items(shows, "shows")) {
                val o = el.jObject() ?: continue
                val show = o["show"].jObject() ?: o
                val view = titleFor(show, "series") ?: continue
                var best: SE? = null
                for (season in (o["seasons"] as? JsonArray).orEmpty()) {
                    val sObj = season.jObject() ?: continue
                    val number = sObj["number"].jInt() ?: continue
                    if (number <= 0) continue
                    for (ep in (sObj["episodes"] as? JsonArray).orEmpty()) {
                        val n = ep.jObject()?.get("number").jInt() ?: continue
                        val se = SE(number, n)
                        if (best == null || se > best) best = se
                    }
                }
                view.watched = best
            }

            for (el in items(movies, "movies")) {
                val o = el.jObject() ?: continue
                val movie = o["movie"].jObject() ?: continue
                val view = titleFor(movie, "movie") ?: continue
                if (o["status"].jStr() == "completed") view.completed = true
            }
            return byId.values.toList()
        }

        private fun items(root: JsonElement, key: String): List<JsonElement> {
            val array = root as? JsonArray ?: root.jObject()?.get(key) as? JsonArray ?: return emptyList()
            return array.toList()
        }

        private fun idOf(ids: JsonObject?): String? {
            ids ?: return null
            ids["imdb"].jStr()?.takeIf { it.isNotBlank() }?.let { return it }
            return ids["tmdb"].jInt()?.let { "tmdb:$it" }
        }
    }
}

/**
 * Milliseconds since the epoch for an ISO-8601 time such as `2026-10-16T14:03:22Z` or `2026-10-16T16:03:22+02:00`.
 * Null for anything it does not recognise.
 */
internal fun parseIsoMillis(text: String?): Long? {
    val m = ISO.find(text?.trim() ?: return null) ?: return null
    val g = m.groupValues
    val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
    val seconds = days * 86_400L + g[4].toInt() * 3_600L + g[5].toInt() * 60L + (g[6].toIntOrNull() ?: 0)
    val zone = g[7]
    val offset = when {
        zone.isEmpty() || zone == "Z" -> 0L
        else -> {
            val sign = if (zone[0] == '-') -1L else 1L
            val digits = zone.drop(1).replace(":", "")
            sign * (digits.take(2).toLong() * 3_600L + digits.drop(2).toLong() * 60L)
        }
    }
    return (seconds - offset) * 1000L
}

private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")

/** Days from 1970-01-01 to the given civil date (proleptic Gregorian). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

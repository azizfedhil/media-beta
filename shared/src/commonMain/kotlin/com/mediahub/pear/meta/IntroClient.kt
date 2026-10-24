package com.mediahub.pear.meta

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One skippable stretch of an episode or movie, in seconds (spec: `SkipSegment`). */
data class SkipSegment(val kind: Kind, val start: Double, /** null = runs to the end of the media. */ val end: Double?) {
    enum class Kind(val raw: String) { Intro("intro"), Recap("recap"), Credits("credits"), Preview("preview") }

    val id: String get() = "${kind.raw}-${start.toInt()}"
    val label: String
        get() = when (kind) {
            Kind.Intro -> "Skip Intro"
            Kind.Recap -> "Skip Recap"
            Kind.Credits -> "Skip Credits"
            Kind.Preview -> "Skip Preview"
        }
}

/**
 * Intro / recap / credits / preview times from TheIntroDB (community-verified, free, no key for reads), spec: `IntroClient`.
 * `GET https://api.theintrodb.org/v4/media?tmdb_id=...&season=...&episode=...` answers
 * `{ intro: [{start_ms, end_ms}], recap: [...], credits: [...], preview: [...] }`; a missing start means the beginning
 * of the media, a missing end means the end.
 */
class IntroClient(
    private val http: Http,
    /** `skip.enabled` (on by default). */
    private val enabled: () -> Boolean = { true },
    /** TMDB's numeric id for a title (`TmdbClient.tmdbIdentifier`). */
    private val tmdbId: suspend (id: String, type: String) -> Int?,
    private val retryDelayMillis: Long = 2_000,
) {
    private val lock = Mutex()
    private val cache = HashMap<String, List<SkipSegment>>()

    private sealed interface Fetch {
        class Found(val segments: List<SkipSegment>) : Fetch
        data object None : Fetch
        data object Failed : Fetch
    }

    val isEnabled: Boolean get() = enabled()

    suspend fun segments(item: MetaPreview, imdb: String, season: Int?, episode: Int?): List<SkipSegment> {
        if (!enabled()) return emptyList()
        val key = "${item.id}:${season ?: 0}:${episode ?: 0}"
        lock.withLock { cache[key] }?.let { return it }

        // TMDB id first (most accurate), then IMDb: a title missing under one id is often present under the other.
        val ids = ArrayList<Pair<String, String>>()
        tmdbId(item.id, item.type)?.let { ids.add("tmdb_id" to it.toString()) }
        if (imdb.startsWith("tt")) ids.add("imdb_id" to imdb)
        if (ids.isEmpty()) return emptyList()

        var answered = false
        for (id in ids) {
            val q = LinkedHashMap<String, String>()
            q[id.first] = id.second
            if (season != null && episode != null) {
                q["season"] = season.toString()
                q["episode"] = episode.toString()
            }
            when (val r = fetch(q)) {
                is Fetch.Found -> {
                    lock.withLock { cache[key] = r.segments }
                    return r.segments
                }
                Fetch.None -> answered = true
                Fetch.Failed -> {}
            }
        }
        // The service answered: really nothing there. A network failure is not remembered.
        if (answered) lock.withLock { cache[key] = emptyList() }
        return emptyList()
    }

    /** One request, retried once after a short pause if the network or the server hiccups. */
    private suspend fun fetch(query: Map<String, String>): Fetch {
        val url = attempt { buildUrl("https://api.theintrodb.org/v4/media", query) } ?: return Fetch.Failed
        for (n in 0 until 2) {
            if (n > 0) delay(retryDelayMillis)
            val reply = attempt { http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json"), timeoutMillis = 10_000)) } ?: continue
            if (reply.status == 404) return Fetch.None
            if (reply.status != 200) continue
            val segs = parse(reply.text)
            return if (segs.isEmpty()) Fetch.None else Fetch.Found(segs)
        }
        return Fetch.Failed
    }

    companion object {
        internal fun parse(text: String): List<SkipSegment> {
            val root = attempt { PearJson.parseToJsonElement(text) as? JsonObject } ?: return emptyList()
            val out = ArrayList<SkipSegment>()
            fun add(kind: SkipSegment.Kind, el: JsonElement?) {
                val arr = el as? JsonArray ?: return
                for (s in arr) {
                    val o = s as? JsonObject ?: continue
                    val start = maxOf((o["start_ms"].jDouble() ?: 0.0) / 1000, 0.0)
                    val end = o["end_ms"].jDouble()?.let { it / 1000 }
                    if (end != null && end - start < 3) continue
                    out.add(SkipSegment(kind, start, end))
                }
            }
            add(SkipSegment.Kind.Recap, root["recap"])
            add(SkipSegment.Kind.Intro, root["intro"])
            add(SkipSegment.Kind.Credits, root["credits"])
            add(SkipSegment.Kind.Preview, root["preview"])
            return out.sortedBy { it.start }
        }
    }
}

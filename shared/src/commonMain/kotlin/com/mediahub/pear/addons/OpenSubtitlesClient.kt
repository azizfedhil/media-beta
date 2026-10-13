package com.mediahub.pear.addons

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.model.SubLanguages
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.attempt
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * OpenSubtitles through the public Stremio OpenSubtitles v3 endpoint: no account or API key (spec: `OpenSubtitlesClient`).
 * `GET {base}/subtitles/{movie|series}/{imdb}[:{season}:{episode}].json` answers `{ subtitles: [{ id, url, lang }] }`.
 * Answers are kept for the run, an empty one too: a title with no subtitles isn't asked about again.
 */
class OpenSubtitlesClient(
    private val http: Http,
    /** `subs.online` (on by default). */
    private val enabled: () -> Boolean = { true },
    /** `subs.baseURL`: another server speaking the same protocol. null or empty = the public one. */
    private val baseUrl: () -> String? = { null },
) {
    private val lock = Mutex()
    private val cache = HashMap<String, List<OnlineSubtitle>>()

    val isEnabled: Boolean get() = enabled()

    /** Subtitles for a title. Empty when anything fails (subtitles are optional, so there is nothing to report). */
    suspend fun search(imdb: String, season: Int?, episode: Int?): List<OnlineSubtitle> {
        if (!enabled() || !imdb.startsWith("tt")) return emptyList()
        val isSeries = season != null && episode != null
        val key = if (isSeries) "$imdb:$season:$episode" else imdb
        lock.withLock { cache[key] }?.let { return it }
        val base = baseUrl()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE
        val url = base.trim('/') + "/subtitles/${if (isSeries) "series" else "movie"}/$key.json"
        if (attempt { Url(url) } == null) return emptyList()
        val reply = attempt { http.execute(HttpRequest(url, timeoutMillis = 15_000)) } ?: return emptyList()
        if (reply.status != 200) return emptyList()
        val parsed = attempt { parse(reply.text) } ?: return emptyList()
        lock.withLock { cache[key] = parsed }
        return parsed
    }

    companion object {
        const val DEFAULT_BASE = "https://opensubtitles-v3.strem.io"

        internal fun parse(text: String): List<OnlineSubtitle> {
            val root = PearJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            val list = root["subtitles"] as? JsonArray ?: return emptyList()
            return list.withIndex().mapNotNull { (i, el) ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val url = o["url"].jStr() ?: return@mapNotNull null
                val lang = o["lang"].jStr()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val id = o["id"].jStr() ?: o["id"].jInt()?.toString() ?: "$i-$lang"
                OnlineSubtitle(id, url, lang)
            }
        }

        /**
         * What the player lists: up to [perPreferred] files in the preferred language first, then the best file of
         * every other language.
         */
        fun choices(all: List<OnlineSubtitle>, preferred: String?, perPreferred: Int = 3): List<OnlineSubtitle> {
            val out = ArrayList<OnlineSubtitle>()
            if (preferred != null && preferred != "off") {
                out += all.filter { SubLanguages.matches(it.lang, preferred) }.take(perPreferred)
            }
            val seen = HashSet<String>()
            for (s in out) seen.add(SubLanguages.canonical(s.lang) ?: s.lang)
            for (s in all) {
                if (seen.add(SubLanguages.canonical(s.lang) ?: s.lang)) out.add(s)
            }
            return out
        }
    }
}

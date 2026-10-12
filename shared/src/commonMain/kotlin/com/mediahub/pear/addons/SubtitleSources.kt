package com.mediahub.pear.addons

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.attempt
import io.ktor.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Online subtitle files for an episode or movie (Batch 4.4b): OpenSubtitles, and each enabled add-on that declares a
 * `subtitles` resource for the title. A Stremio add-on answers `GET {base}/subtitles/{type}/{id}.json` in the same shape
 * OpenSubtitles does. OpenSubtitles comes first in the list, add-ons follow in the user's add-on order, and a file that
 * appears twice (same link) is listed once. Answers are kept for the run, empty ones too, as OpenSubtitles already does.
 *
 * iOS looks only at OpenSubtitles; the add-on sources are a desktop addition, as the plan asked for them.
 */
class SubtitleSources(
    private val http: Http,
    private val openSubtitles: OpenSubtitlesClient,
    private val addons: () -> List<Addon>,
) {
    private val lock = Mutex()
    private val cache = HashMap<String, List<OnlineSubtitle>>()

    /** `subs.online` switches all of it off. [imdb] is the title's IMDb id; [season] and [episode] are set for a show. */
    suspend fun search(imdb: String, season: Int?, episode: Int?): List<OnlineSubtitle> {
        if (!openSubtitles.isEnabled || !imdb.startsWith("tt")) return emptyList()
        val isSeries = season != null && episode != null
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries) "$imdb:$season:$episode" else imdb
        val key = "$type:$id"
        lock.withLock { cache[key] }?.let { return it }

        val merged = coroutineScope {
            val fromOpenSubtitles = async { openSubtitles.search(imdb, season, episode) }
            val fromAddons = addons()
                .filter { it.provides("subtitles", type, id) }
                .map { addon -> async { fromAddon(addon, type, id) } }
            (listOf(fromOpenSubtitles.await()) + fromAddons.map { it.await() })
                .flatten()
                .distinctBy { it.url }
        }
        lock.withLock { cache[key] = merged }
        return merged
    }

    /** One add-on's answer, or nothing when it fails or is slow. Its ids are prefixed with the add-on, so they stay unique. */
    private suspend fun fromAddon(addon: Addon, type: String, id: String): List<OnlineSubtitle> {
        val url = addon.baseUrl + "subtitles/$type/${encodeId(id)}.json"
        if (attempt { Url(url) } == null) return emptyList()
        val reply = attempt {
            withTimeoutOrNull(DEADLINE_MILLIS) {
                http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json"), timeoutMillis = DEADLINE_MILLIS))
            }
        }?.takeIf { it.status == 200 } ?: return emptyList()
        val parsed = attempt { OpenSubtitlesClient.parse(reply.text) } ?: return emptyList()
        return parsed.map { it.copy(id = "${addon.id}|${it.id}", source = addon.manifest.name) }
    }

    private companion object {
        const val DEADLINE_MILLIS = 10_000L
    }
}

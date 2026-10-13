package com.mediahub.pear.player

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.Pin
import com.mediahub.pear.addons.PinnedSources
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.history.EpisodeLoader
import com.mediahub.pear.history.UpNext
import com.mediahub.pear.meta.TmdbClient
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import kotlinx.coroutines.CancellationException

// Which episode comes after the one playing, and which stream plays it (Batch 4.3d). Spec: `EpisodeSupport.swift`
// (`SourceResolver`) and the `next` / `resolve` of `EpisodeProvider` in PlayerView.swift. The network calls go through
// the existing clients; the choice of stream is [SourceChooser], which is pure.

/**
 * Looks up the next episode and resolves a stream for it. A failed lookup reads as "nothing there", never as an error
 * on screen: the player then just has no next episode, or says it found no source.
 */
class EpisodeSupport(
    private val episodes: EpisodeLoader,
    private val tmdb: TmdbClient,
    private val addonClient: AddonClient,
    private val addons: () -> List<Addon>,
    private val pinned: PinnedSources,
    /** Whether torrent sources can be played now (P2P on, network allowed). Read at each choice. */
    private val p2pReady: () -> Boolean = { false },
) {
    /** The episode after [request]'s, if it exists and has aired. Null for a movie, or when nothing comes after. */
    suspend fun next(request: PlayRequest): Pair<Int, EpisodeItem>? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        return lenient<Pair<Int, EpisodeItem>?>(null) {
            UpNext.next(season, episode, load = { s ->
                episodes.load(request.item.id, request.item.type, s) { stremioId(request.item) }
            })
        }
    }

    /**
     * A playable stream for [episode] of [current]'s show. Prefers the source being watched now (same add-on and
     * name), then the pinned source, then any playable stream. Null when there is none.
     */
    suspend fun resolve(current: PlayRequest, season: Int, episode: EpisodeItem): PlayRequest? =
        lenient<PlayRequest?>(null) {
            val addonList = addons()
            val groups = addonClient.streams("${current.imdb}:$season:${episode.id}", current.item.type, addonList)
            val ordered = AddonClient.ordered(groups, addonList)
            val (addon, stream) = SourceChooser.choose(
                currentAddonID = current.sourceAddonID,
                currentSignature = current.sourceSignature,
                pin = pinned.pin(current.imdb),
                p2pReady = p2pReady(),
                groups = ordered,
            ) ?: return@lenient null
            SourceChooser.request(current, season, episode, addon, stream)
        }

    /** The IMDb id the episode loader needs for TheTVDB. TMDB-keyed titles are mapped; IMDb ids are used as they are. */
    private suspend fun stremioId(item: MetaPreview): String? =
        if (item.id.startsWith("tmdb:")) item.id.removePrefix("tmdb:").toIntOrNull()?.let { tmdb.imdbId(it, item.type) }
        else item.id

    private suspend fun <T> lenient(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
}

/** Picks the stream for another episode, and builds its request (`SourceResolver.request` in EpisodeSupport.swift). */
object SourceChooser {
    /**
     * The first match of: the add-on and name being played now, the pinned source, the first playable stream of any
     * add-on. [groups] must already be in the user's add-on order. Within an add-on, a name that is no longer there
     * falls back to that add-on's first playable stream. Torrents count only while P2P is on and the network allows it.
     */
    fun choose(
        currentAddonID: String?,
        currentSignature: String?,
        pin: Pin?,
        groups: List<Pair<Addon, List<StreamItem>>>,
        p2pReady: Boolean = false,
    ): Pair<Addon, StreamItem>? {
        fun match(addonID: String, signature: String?): Pair<Addon, StreamItem>? {
            val group = groups.firstOrNull { it.first.id == addonID } ?: return null
            val playable = group.second.filter { it.isPlayable(p2pReady = p2pReady) }
            val stream = playable.firstOrNull { it.signature == signature } ?: playable.firstOrNull() ?: return null
            return group.first to stream
        }

        if (currentAddonID != null) match(currentAddonID, currentSignature)?.let { return it }
        if (pin != null) match(pin.addonID, pin.signature)?.let { return it }
        for ((addon, streams) in groups) {
            streams.firstOrNull { it.isPlayable(p2pReady = p2pReady) }?.let { return addon to it }
        }
        return null
    }

    /** The request for [episode] through [stream]. Keeps the title, the logo and the IMDb id; takes the episode's still. */
    fun request(current: PlayRequest, season: Int, episode: EpisodeItem, addon: Addon, stream: StreamItem): PlayRequest? {
        val url = stream.url ?: return null
        return PlayRequest(
            url = url,
            headers = stream.requestHeaders,
            item = current.item,
            key = "$season:${episode.id}",
            imdb = current.imdb,
            season = season,
            episode = episode.id,
            episodeTitle = episode.name,
            logo = current.logo,
            thumb = episode.image ?: current.item.backdropUrl,
            sourceAddonID = addon.id,
            sourceSignature = stream.signature,
        )
    }
}

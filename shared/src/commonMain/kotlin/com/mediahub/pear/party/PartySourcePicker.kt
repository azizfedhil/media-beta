package com.mediahub.pear.party

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.Pin
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.p2p.p2pSource
import com.mediahub.pear.player.SourceChooser
import kotlinx.coroutines.CancellationException

// Batch K6: a guest finds the room's source on its own add-ons. The room sends a source descriptor, never a URL
// (cloud/CLOUD_API.md section 3.4), so every stream here comes from this device.

/** How a room's source was matched on this device. */
enum class PartyMatch { SameStream, SameTorrent, Usual }

/** The outcome of finding a room's source on this device. */
sealed interface PartyPick {
    /** A stream to open. [note] is the short line that says where it came from. */
    data class Found(val request: PlayRequest, val match: PartyMatch, val note: String) : PartyPick

    /** No stream here. [reason] is one or two full sentences the player or the title page can show. */
    data class Missing(val reason: String) : PartyPick
}

/**
 * Picks this device's own stream for a room's source, in this order: the same add-on and stream name the host used; the
 * same torrent (only while P2P is on and the network allows it, preferring the same file in it); then the usual pick for
 * the title, which is the pinned source or the first playable stream.
 */
object PartySourcePicker {

    /** Why torrents can't play on this device right now, or null when they can. */
    fun p2pOffReason(enabled: Boolean, canStreamNow: Boolean): String? = when {
        !enabled -> "Peer-to-peer is off on this device."
        !canStreamNow -> "Peer-to-peer isn't allowed on this network."
        else -> null
    }

    /**
     * The choice from [groups], already in the user's add-on order. Pure, so it reads the same wherever it runs.
     * [p2pOff] is the reason torrents can't play, or null when they can.
     */
    fun choose(
        source: PartySource,
        groups: List<Pair<Addon, List<StreamItem>>>,
        pin: Pin?,
        p2pOff: String?,
    ): Pair<Pair<Addon, StreamItem>, PartyMatch>? {
        val p2pReady = p2pOff == null

        // 1. The same add-on and the same stream name.
        val addonId = source.addonId
        val signature = source.signature
        if (addonId != null && signature != null) {
            val group = groups.firstOrNull { it.first.id == addonId }
            val same = group?.second?.firstOrNull { it.signature == signature && it.isPlayable(p2pReady) }
            if (group != null && same != null) return Pair(Pair(group.first, same), PartyMatch.SameStream)
        }

        // 2. The same torrent, preferring the same file inside it.
        val hash = source.p2pHash
        if (hash != null && p2pReady) {
            val torrents = ArrayList<Pair<Addon, StreamItem>>()
            for ((addon, streams) in groups) {
                for (stream in streams) {
                    if (stream.isTorrent && stream.infoHash.equals(hash, ignoreCase = true)) torrents.add(Pair(addon, stream))
                }
            }
            val sameFile = torrents.firstOrNull { it.second.fileIdx == source.fileIdx }
            val any = sameFile ?: torrents.firstOrNull()
            if (any != null) return Pair(any, PartyMatch.SameTorrent)
        }

        // 3. The usual pick for the title.
        val usual = SourceChooser.choose(currentAddonID = null, currentSignature = null, pin = pin, groups = groups, p2pReady = p2pReady)
        return usual?.let { Pair(it, PartyMatch.Usual) }
    }

    /**
     * Finds the room's source on this device: looks up the episode (or the film) on the enabled add-ons, then [choose]. Never
     * throws: a failed lookup reads as "not here", with the reason. Cancellation is passed on.
     */
    suspend fun pick(
        source: PartySource,
        addonClient: AddonClient,
        addons: List<Addon>,
        pin: Pin?,
        p2pOff: String?,
    ): PartyPick {
        if (addons.isEmpty()) return PartyPick.Missing("No add-ons are turned on.")
        val series = source.season != null && source.episode != null
        val lookup = if (series) "${source.imdb}:${source.season}:${source.episode}" else source.imdb
        val groups = try {
            AddonClient.ordered(addonClient.streams(lookup, source.type, addons), addons)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PartyPick.Missing("Your add-ons didn't answer.")
        }

        val picked = choose(source, groups, pin, p2pOff)
            ?: return PartyPick.Missing(
                if (source.p2pHash != null && p2pOff != null) p2pOff + " None of your add-ons has a link for this."
                else "None of your add-ons has a stream for this.",
            )
        val (chosen, match) = picked
        val (addon, stream) = chosen
        val url = stream.url ?: stream.p2pSource()?.let { "p2p://${it.infoHash}" }
            ?: return PartyPick.Missing("That stream can't be played here.")

        val request = PlayRequest(
            url = url,
            headers = stream.requestHeaders,
            item = source.toMetaPreview(),
            key = if (series) "${source.season}:${source.episode}" else "movie",
            imdb = source.imdb,
            season = if (series) source.season else null,
            episode = if (series) source.episode else null,
            episodeTitle = source.episodeTitle,
            thumb = source.poster,
            p2p = stream.p2pSource(),
            sourceAddonID = addon.id,
            sourceSignature = stream.signature,
            partyRoom = true,
            partyFor = source,
        )
        return PartyPick.Found(request, match, note(match, addon, source, skippedTorrent = source.p2pHash != null && p2pOff != null, p2pOff = p2pOff))
    }

    /** The line the player shows: where the stream came from, and why a torrent of the host's was not used. */
    private fun note(match: PartyMatch, addon: Addon, source: PartySource, skippedTorrent: Boolean, p2pOff: String?): String {
        val name = addon.manifest.name
        val skipped = if (skippedTorrent && p2pOff != null) " Torrents skipped. $p2pOff" else ""
        return when (match) {
            PartyMatch.SameStream -> "Your source: $name, the same as the host's.$skipped"
            PartyMatch.SameTorrent -> "Your source: $name, the same torrent as the host's."
            PartyMatch.Usual ->
                if (source.addonId == null) "Your source: $name.$skipped"
                else "Your source: $name. The host's isn't on this device.$skipped"
        }
    }
}

/** Where the room is right now, in seconds, or null when the room has no state yet. */
fun PartyService.roomPositionNow(): Double? =
    client.session.state.value.state?.let { DriftController.targetPosition(it, client.serverNowMs()) }

/**
 * A stream the guest picked by hand for the room's own episode stays the guest's, but its player is still driven by the room:
 * play, pause and seek follow the host (batch K6). Any other request is returned as it is.
 */
fun PlayRequest.forPartyRoom(party: PartyService): PlayRequest {
    if (party.role.value != PartyRole.Guest) return this
    val room = party.session.value.state?.source ?: return this
    val sameEpisode = room.imdb == imdb && room.season == season && room.episode == episode
    return if (sameEpisode) copy(partyRoom = true, partyFor = room) else this
}

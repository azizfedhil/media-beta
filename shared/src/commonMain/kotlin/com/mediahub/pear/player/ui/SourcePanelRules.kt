package com.mediahub.pear.player.ui

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.StreamItem

// Rules behind the player's Sources panel (Batch 4.3c). Pure, so they are unit tested.

/**
 * The sources the player can actually open: playable ones only, grouped by add-on, and add-ons with nothing playable
 * left out. P2P is still off here (Batch 4.5 brings torrents in), so torrent-only results are not listed.
 */
fun playableSourceGroups(groups: List<Pair<Addon, List<StreamItem>>>): List<Pair<Addon, List<StreamItem>>> =
    groups
        .map { (addon, items) -> addon to items.filter { it.isPlayable(p2pReady = false) } }
        .filter { (_, items) -> items.isNotEmpty() }

/**
 * Narrows the list to one add-on (`SourceFilter.apply` in SourceFilter.swift). A stale id, an add-on that answered
 * nothing this time, shows everything.
 */
fun sourceGroupsShown(
    groups: List<Pair<Addon, List<StreamItem>>>,
    selectedAddonID: String?,
): List<Pair<Addon, List<StreamItem>>> {
    if (selectedAddonID == null || groups.none { it.first.id == selectedAddonID }) return groups
    return groups.filter { it.first.id == selectedAddonID }
}

/**
 * Whether [stream] is the one being played: same add-on and same stream name (the signature, which survives the
 * link changing per episode). Picking it again just closes the panel.
 */
fun isCurrentSource(currentAddonID: String?, currentSignature: String?, addon: Addon, stream: StreamItem): Boolean =
    addon.id == currentAddonID && stream.signature == currentSignature

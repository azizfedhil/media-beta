package com.mediahub.pear.model

import com.mediahub.pear.p2p.P2PSource
import com.mediahub.pear.party.PartySource

data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val item: MetaPreview,
    val key: String,
    val imdb: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
    val sourceAddonID: String? = null,
    val sourceSignature: String? = null,
    /** The torrent to open through the P2P engine, when the stream is one. Null for a link. */
    val p2p: P2PSource? = null,
    /** True for a guest's player that a watch party's room opened: the room's host decides what plays (batch K6). */
    val partyRoom: Boolean = false,
    /** The room source this request was resolved for, when a watch party's room opened it. Null otherwise (batch K6). */
    val partyFor: PartySource? = null,
)

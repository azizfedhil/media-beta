package com.mediahub.pear.model

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
)
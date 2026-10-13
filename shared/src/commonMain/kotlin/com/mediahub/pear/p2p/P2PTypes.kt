package com.mediahub.pear.p2p

/** What the player asks the engine for: a torrent by info hash, its trackers, and the file Torrentio picked. */
data class P2PSource(
    val infoHash: String,
    val trackers: List<String> = emptyList(),
    val fileIdx: Int? = null,
)

/**
 * A started stream: the loopback URL the player reads, and the file behind it. The URL carries a token that lasts for the
 * session, so it is never logged or shown.
 */
data class P2PStream(
    val url: String,
    val fileName: String,
    val length: Long,
    val fileIndex: Int,
)

/** The numbers of the stream that is open (spec: `EngineStats`). */
data class P2PStats(
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val peersLive: Int,
    val downloadBps: Long,
)

/** Whether the engine exists, and the open stream's numbers when there is one. */
data class P2PStatus(
    val engineLoaded: Boolean,
    val stats: P2PStats?,
)

/** Why P2P can't play something. The messages are the ones the player shows (spec: `P2PManager.Failure`). */
sealed class P2PFailure(message: String) : Exception(message) {
    /** The engine's library is missing or would not load. The message says where it looked. */
    class Unavailable(message: String) : P2PFailure(message)

    object NoPeers : P2PFailure("Couldn't find peers for this torrent. Try another source.")

    object NoVideo : P2PFailure("This torrent has no playable video file.")

    /** Stopped while it was starting: not an error to show. */
    object Cancelled : P2PFailure("Stopped.")

    class Engine(message: String) : P2PFailure("Peer-to-peer engine error: $message")
}

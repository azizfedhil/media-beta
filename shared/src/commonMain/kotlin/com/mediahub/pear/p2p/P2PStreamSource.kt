package com.mediahub.pear.p2p

import com.mediahub.pear.model.StreamItem

/**
 * The torrent behind a stream, as the engine needs it: its hash, its trackers, and the file Torrentio picked (a season pack
 * has many files). Null for a link, which plays directly.
 */
fun StreamItem.p2pSource(): P2PSource? {
    if (!isTorrent) return null
    val hash = infoHash ?: return null
    return P2PSource(infoHash = hash, trackers = trackers, fileIdx = fileIdx)
}

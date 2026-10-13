package com.mediahub.pear.p2p

/**
 * The line under the loader, or the buffering spinner, while a torrent opens or buffers: peers, speed, progress. Before the
 * stream has numbers it says it is still looking for peers.
 */
fun p2pStatusLine(stats: P2PStats?): String {
    if (stats == null) return "Finding peers…"
    val parts = ArrayList<String>()
    parts.add(if (stats.peersLive == 1) "1 peer" else "${stats.peersLive} peers")
    if (stats.downloadBps > 0) parts.add("${rate(stats.downloadBps)}/s")
    if (stats.totalBytes > 0) parts.add("${stats.downloadedBytes * 100 / stats.totalBytes}%")
    return parts.joinToString(" · ")
}

/** A byte rate as KB or MB with one decimal place. */
internal fun rate(bytesPerSecond: Long): String =
    if (bytesPerSecond >= 1_048_576) {
        val tenths = bytesPerSecond * 10 / 1_048_576
        "${tenths / 10}.${tenths % 10} MB"
    } else {
        "${bytesPerSecond / 1024} KB"
    }

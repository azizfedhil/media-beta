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

/** True while no peer has been found yet: the engine has no numbers, or has them and none of its peers is live. */
fun isLookingForPeers(stats: P2PStats?): Boolean = stats == null || stats.peersLive == 0

/** How long each phrase of [findingPeersPhrase] stays up. */
const val FINDING_PEERS_PHRASE_SECONDS = 4

private val findingPeersPhrases = listOf(
    "Finding peers…",
    "Asking around the swarm…",
    "Knocking on strangers' doors…",
    "Shaking the pear. tree…",
    "Waving at the internet…",
    "Bribing seeders with good vibes…",
    "Sending out the pigeons…",
    "Whistling for peers…",
    "Checking behind the couch cushions…",
    "Shouting into the void…",
    "Persuading the router…",
    "Rounding up the swarm…",
    "Peers are shy today…",
    "Following the crumbs…",
    "Polishing the secret handshake…",
)

/**
 * What to say while still looking for peers, given how many seconds the search has taken. It starts with the plain
 * "Finding peers…", then moves to the next phrase every [FINDING_PEERS_PHRASE_SECONDS] seconds, and starts over after the last.
 */
fun findingPeersPhrase(secondsSearching: Int): String =
    findingPeersPhrases[(secondsSearching.coerceAtLeast(0) / FINDING_PEERS_PHRASE_SECONDS) % findingPeersPhrases.size]

/** A byte rate as KB or MB with one decimal place. */
internal fun rate(bytesPerSecond: Long): String =
    if (bytesPerSecond >= 1_048_576) {
        val tenths = bytesPerSecond * 10 / 1_048_576
        "${tenths / 10}.${tenths % 10} MB"
    } else {
        "${bytesPerSecond / 1024} KB"
    }

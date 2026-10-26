package com.mediahub.pear.p2p

import kotlin.random.Random

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

/** How long each phrase of [FindingPeersPhrases] stays up. */
const val FINDING_PEERS_PHRASE_SECONDS = 4

private const val PLAIN_PHRASE = "Finding peers…"

private val playfulPhrases = listOf(
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
 * What to say while still looking for peers. The first phrase is the plain "Finding peers…"; after it, [next] hands out the
 * playful ones in a random order, each once before any comes round again, and never the same one twice in a row. Ask for a
 * new one every [FINDING_PEERS_PHRASE_SECONDS] seconds, and call [restart] when peers are found, so the next search begins
 * with the plain line again.
 */
class FindingPeersPhrases(private val random: Random = Random.Default) {
    private val queue = ArrayDeque<String>()
    private var last: String? = null
    private var started = false

    fun next(): String {
        if (!started) {
            started = true
            last = PLAIN_PHRASE
            return PLAIN_PHRASE
        }
        if (queue.isEmpty()) refill()
        return queue.removeFirst().also { last = it }
    }

    fun restart() {
        started = false
        queue.clear()
        last = null
    }

    private fun refill() {
        val round = playfulPhrases.shuffled(random)
        // A new round must not open with the phrase the last one ended on.
        queue.addAll(if (round.size > 1 && round.first() == last) round.drop(1) + round.first() else round)
    }
}

/** A byte rate as KB or MB with one decimal place. */
internal fun rate(bytesPerSecond: Long): String =
    if (bytesPerSecond >= 1_048_576) {
        val tenths = bytesPerSecond * 10 / 1_048_576
        "${tenths / 10}.${tenths % 10} MB"
    } else {
        "${bytesPerSecond / 1024} KB"
    }

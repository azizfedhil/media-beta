package com.mediahub.pear.p2p

import com.mediahub.pear.model.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class P2PStreamTest {

    @Test
    fun aTorrentBecomesAnEngineSourceWithItsTrackersAndFile() {
        val s = StreamItem(
            name = "1080p",
            infoHash = "a".repeat(40),
            sources = listOf("tracker:udp://t.example:1/announce", "dht:abc"),
            fileIdx = 2,
        )
        assertEquals(P2PSource("a".repeat(40), listOf("udp://t.example:1/announce"), 2), s.p2pSource())
    }

    @Test
    fun aLinkIsNotAnEngineSource() {
        assertNull(StreamItem(name = "debrid", url = "https://example.com/video.mp4").p2pSource())
    }

    @Test
    fun theStatusLineSaysWhatTheEngineIsDoing() {
        assertEquals("Finding peers…", p2pStatusLine(null))
        assertEquals(
            "1 peer · 2.0 MB/s · 50%",
            p2pStatusLine(P2PStats(downloadedBytes = 50, uploadedBytes = 0, totalBytes = 100, peersLive = 1, downloadBps = 2_097_152)),
        )
        assertEquals("0 peers", p2pStatusLine(P2PStats(0, 0, 0, 0, 0)))
        assertEquals("3 peers · 300 KB/s", p2pStatusLine(P2PStats(0, 0, 0, 3, 300L * 1024)))
    }
}

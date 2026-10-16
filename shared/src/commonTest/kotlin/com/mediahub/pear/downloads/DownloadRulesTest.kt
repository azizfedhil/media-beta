package com.mediahub.pear.downloads

import com.mediahub.pear.model.DownloadRecord
import com.mediahub.pear.model.DownloadState
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.model.blocksReplacement
import com.mediahub.pear.model.downloadUrl
import com.mediahub.pear.model.forSource
import com.mediahub.pear.model.fromOtherSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stream(url: String?) = StreamItem(name = "Source", url = url)

private fun record(url: String, state: DownloadState = DownloadState.Done) = DownloadRecord(
    id = "tt1",
    imdb = "tt1",
    title = "Film",
    url = url,
    fileName = "tt1.mp4",
    state = state,
)

class DownloadRulesTest {
    // The link gate: the iOS rule (https, not a playlist), plus manifests.

    @Test
    fun anHttpsFileIsDownloadable() {
        assertEquals("https://cdn.test/film.mp4", stream("https://cdn.test/film.mp4").downloadUrl)
    }

    @Test
    fun plainHttpIsRefused() {
        assertNull(stream("http://cdn.test/film.mp4").downloadUrl)
    }

    @Test
    fun playlistsAndManifestsAreRefused() {
        assertNull(stream("https://cdn.test/film.m3u8").downloadUrl)
        assertNull(stream("https://cdn.test/film.mpd").downloadUrl)
        assertNull(stream("https://cdn.test/film.m3u8?token=1").downloadUrl)
    }

    @Test
    fun aQueryStringDoesNotDecideTheExtension() {
        assertEquals("https://cdn.test/film.mp4?token=m3u8", stream("https://cdn.test/film.mp4?token=m3u8").downloadUrl)
    }

    @Test
    fun noLinkIsNotDownloadable() {
        assertNull(stream(null).downloadUrl)
    }

    // Per-source state: a title's download belongs to the source it came from.

    @Test
    fun aRecordMatchesOnlyItsOwnLink() {
        val records = listOf(record("https://a.test/x.mp4"))
        assertNotNull(records.forSource("tt1", "https://a.test/x.mp4"))
        assertNull(records.forSource("tt1", "https://b.test/y.mp4"))
        assertNotNull(records.fromOtherSource("tt1", "https://b.test/y.mp4"))
        assertNull(records.fromOtherSource("tt1", "https://a.test/x.mp4"))
    }

    @Test
    fun aStoppedDownloadCanBeReplacedAndAnActiveOneCannot() {
        assertFalse(DownloadState.Failed.blocksReplacement)
        assertFalse(DownloadState.Paused.blocksReplacement)
        assertTrue(DownloadState.Queued.blocksReplacement)
        assertTrue(DownloadState.Downloading.blocksReplacement)
        assertTrue(DownloadState.Done.blocksReplacement)
    }

    // Failures in words a person can act on.

    @Test
    fun anHttpStatusReadsAsASentence() {
        assertTrue("HTTP 403" in friendlyHttpError(403))
        assertTrue("no longer" in friendlyHttpError(404))
        assertTrue("busy" in friendlyHttpError(429))
        assertTrue("server error" in friendlyHttpError(502))
        assertTrue("418" in friendlyHttpError(418))
    }

    @Test
    fun aWebPageIsRefusedAsTheVideo() {
        assertNotNull(notAVideoMessage("text/html; charset=utf-8"))
        assertNotNull(notAVideoMessage("application/vnd.apple.mpegurl"))
        assertNull(notAVideoMessage("video/mp4"))
        assertNull(notAVideoMessage("application/octet-stream"))
        assertNull(notAVideoMessage(null))
    }
}

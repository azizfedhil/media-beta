package com.mediahub.pear.player

import com.mediahub.pear.LocalParts
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LibraryStatus
import com.mediahub.pear.history.LocalLibrary
import com.mediahub.pear.history.WatchHistory
import com.mediahub.pear.history.WatchLog
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.simkl.ScrobbleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val movie = MetaPreview("tt1", "movie", "A Movie")
private val show = MetaPreview("tt2", "series", "A Show")

private fun entry(position: Double, duration: Double = 6000.0, key: String = "movie", item: MetaPreview = movie) =
    HistoryEntry(item = item, key = key, position = position, duration = duration, updated = 0L)

class ProgressRulesTest {
    @Test
    fun resumesTheSameEpisodePastThirtySeconds() {
        assertEquals(1200.0, ProgressRules.resumePoint(entry(1200.0, key = "1:3", item = show), "1:3"))
    }

    @Test
    fun doesNotResumeAnotherEpisodeAFinishedItemOrTheFirstThirtySeconds() {
        assertNull(ProgressRules.resumePoint(entry(1200.0, key = "1:3", item = show), "1:4"))
        assertNull(ProgressRules.resumePoint(entry(5600.0), "movie")) // 93%: finished
        assertNull(ProgressRules.resumePoint(entry(30.0), "movie"))
        assertNull(ProgressRules.resumePoint(null, "movie"))
    }

    @Test
    fun startAtDropsAResumePointUnderTheThreshold() {
        assertEquals(0.0, ProgressRules.startAt(null))
        assertEquals(0.0, ProgressRules.startAt(12.0))
        assertEquals(0.0, ProgressRules.startAt(30.0))
        assertEquals(31.0, ProgressRules.startAt(31.0))
    }

    @Test
    fun nothingIsSavedBeforeThePositionAndLengthAreKnown() {
        assertTrue(!ProgressRules.canSave(0.0, 6000.0))
        assertTrue(!ProgressRules.canSave(10.0, 0.0))
        assertTrue(!ProgressRules.canSave(Double.NaN, 6000.0))
        assertTrue(ProgressRules.canSave(10.0, 6000.0))
    }

    @Test
    fun watchedLineAndScrobblePercent() {
        assertTrue(ProgressRules.movieWatched(5520.0, 6000.0)) // exactly 92%
        assertTrue(!ProgressRules.movieWatched(5519.0, 6000.0))
        assertEquals(50.0, ProgressRules.scrobblePercent(3000.0, 6000.0))
        assertEquals(100.0, ProgressRules.scrobblePercent(7000.0, 6000.0))
        assertEquals(0.0, ProgressRules.scrobblePercent(10.0, 0.0))
    }

    @Test
    fun watchTimeTicksAreCappedAtTwentySeconds() {
        assertEquals(15.0, ProgressRules.tickSeconds(0L, 15_000L))
        assertEquals(20.0, ProgressRules.tickSeconds(0L, 3_600_000L))
        assertEquals(0.0, ProgressRules.tickSeconds(5_000L, 1_000L)) // clock went backwards
    }

    @Test
    fun theStartGuardAcceptsTheResumePointAndAnythingPastIt() {
        assertTrue(!ProgressRules.hasReachedStart(0.0, 1200.0))
        assertTrue(!ProgressRules.hasReachedStart(1189.0, 1200.0))
        assertTrue(ProgressRules.hasReachedStart(1190.0, 1200.0))
        assertTrue(ProgressRules.hasReachedStart(2000.0, 1200.0))
    }

    @Test
    fun aRequestKnowsItsScrobbleTargetAndKeepsItsSourceWhenTheStreamChanges() {
        val r = PlayRequest(url = "u1", item = show, key = "2:5", imdb = "tt2", season = 2, episode = 5, sourceAddonID = "a", sourceSignature = "s")
        assertEquals(ScrobbleTarget("tt2", 2, 5), r.scrobbleTarget)
        val moved = r.withSource("u2", mapOf("h" to "v"), "b", "t")
        assertEquals("u2", moved.url)
        assertEquals("b", moved.sourceAddonID)
        assertEquals("t", moved.sourceSignature)
        assertEquals("2:5", moved.key)
        assertEquals(2, moved.season)
    }
}

class PlaybackProgressTest {
    private class Rig {
        var clock = 1_000_000L
        val prefs = PrefStore()
        val history = WatchHistory(prefs, now = { clock })
        val log = WatchLog(prefs, now = { clock }, localTime = { LocalParts(2026, 1, 1, 12) })
        val library = LocalLibrary(prefs, now = { clock })
        val scrobbles = mutableListOf<Triple<String, ScrobbleTarget, Double>>()
        val progress = PlaybackProgress(history, log, library, { a, t, p -> scrobbles.add(Triple(a, t, p)) }, now = { clock })
    }

    private val movieRequest = PlayRequest(url = "u", item = movie, key = "movie", imdb = "tt1", thumb = "backdrop.jpg")
    private val episodeRequest = PlayRequest(
        url = "u", item = show, key = "1:3", imdb = "tt2", season = 1, episode = 3, episodeTitle = "Three", thumb = "still.jpg",
    )

    @Test
    fun finishingMidEpisodeSavesTheResumePointWithTheEpisodeFields() {
        val r = Rig()
        r.progress.begin()
        r.progress.finish(episodeRequest, PlaybackSnapshot(1300.0, 2400.0, playing = true))
        val e = r.history.entry("tt2")!!
        assertEquals(1300.0, e.position)
        assertEquals("1:3", e.key)
        assertEquals(1, e.season)
        assertEquals(3, e.episode)
        assertEquals("Three", e.episodeTitle)
        assertEquals("still.jpg", e.thumb)
        assertEquals(listOf("tt2"), r.history.continueWatching.map { it.id })
    }

    @Test
    fun theNextOpenResumesWhereItStopped() {
        val r = Rig()
        r.progress.begin()
        r.progress.finish(movieRequest, PlaybackSnapshot(1800.0, 6000.0, playing = false))
        assertEquals(1800.0, ProgressRules.resumePoint(r.history.entry("tt1"), "movie"))
    }

    @Test
    fun periodicSavesGoToStorageWithoutTouchingTheListsOnScreen() {
        val r = Rig()
        r.progress.begin()
        r.history.update(movie, "movie", 100.0, 6000.0) // already in the list
        r.progress.tick(movieRequest, PlaybackSnapshot(400.0, 6000.0, playing = true))
        assertEquals(100.0, r.history.entry("tt1")!!.position) // in-memory list unchanged, as on iOS
        val reread = WatchHistory(r.prefs, now = { r.clock })
        assertEquals(400.0, reread.entry("tt1")!!.position) // but it is stored
    }

    @Test
    fun pausingBanksTheResumePoint() {
        val r = Rig()
        r.progress.begin()
        r.progress.pausedOrBackgrounded(movieRequest, PlaybackSnapshot(900.0, 6000.0, playing = false))
        val reread = WatchHistory(r.prefs, now = { r.clock })
        assertEquals(900.0, reread.entry("tt1")!!.position)
    }

    @Test
    fun nothingIsSavedWhileTheLengthIsUnknown() {
        val r = Rig()
        r.progress.begin()
        r.progress.finish(movieRequest, PlaybackSnapshot(0.0, 0.0, playing = false))
        assertNull(r.history.entry("tt1"))
        assertTrue(r.scrobbles.isEmpty())
    }

    @Test
    fun closingWhileTheFileIsStillOpeningKeepsTheOldResumePoint() {
        val r = Rig()
        r.history.update(movie, "movie", 3000.0, 6000.0)
        r.progress.begin(resumeAt = 3000.0)
        r.progress.finish(movieRequest, PlaybackSnapshot(0.04, 6000.0, playing = false))
        assertEquals(3000.0, r.history.entry("tt1")!!.position)
    }

    @Test
    fun onceThePlayheadIsAtTheResumePointEverythingSavesAgain() {
        val r = Rig()
        r.history.update(movie, "movie", 3000.0, 6000.0)
        r.progress.begin(resumeAt = 3000.0)
        r.progress.tick(movieRequest, PlaybackSnapshot(3001.0, 6000.0, playing = true))
        // The viewer jumps back to the start: now that is a real position.
        r.progress.finish(movieRequest, PlaybackSnapshot(60.0, 6000.0, playing = true))
        assertEquals(60.0, r.history.entry("tt1")!!.position)
    }

    @Test
    fun finishingIsOnlyDoneOncePerRequest() {
        val r = Rig()
        r.progress.begin()
        r.progress.started(episodeRequest)
        r.progress.finish(episodeRequest, PlaybackSnapshot(1200.0, 2400.0, playing = true))
        r.progress.finish(episodeRequest, PlaybackSnapshot(2000.0, 2400.0, playing = true)) // dispose after onClose
        assertEquals(1200.0, r.history.entry("tt2")!!.position)
        assertEquals(listOf("start", "stop"), r.scrobbles.map { it.first })
    }

    @Test
    fun beginStartsAFreshEpisode() {
        val r = Rig()
        r.progress.begin()
        r.progress.started(episodeRequest)
        r.progress.finish(episodeRequest, PlaybackSnapshot(2300.0, 2400.0, playing = true))
        r.progress.begin()
        val next = episodeRequest.copy(key = "1:4", episode = 4)
        r.progress.started(next)
        r.progress.finish(next, PlaybackSnapshot(100.0, 2400.0, playing = true))
        assertEquals(listOf("start", "stop", "start", "stop"), r.scrobbles.map { it.first })
        assertEquals(ScrobbleTarget("tt2", 1, 4), r.scrobbles.last().second)
    }

    @Test
    fun simklGetsStartAtZeroAndStopWithThePercentWatched() {
        val r = Rig()
        r.progress.begin()
        r.progress.started(movieRequest)
        r.progress.started(movieRequest) // a second Playing edge (after a pause) must not scrobble again
        r.progress.finish(movieRequest, PlaybackSnapshot(3000.0, 6000.0, playing = true))
        assertEquals(2, r.scrobbles.size)
        assertEquals(Triple("start", ScrobbleTarget("tt1"), 0.0), r.scrobbles[0])
        assertEquals(Triple("stop", ScrobbleTarget("tt1"), 50.0), r.scrobbles[1])
    }

    @Test
    fun aRequestWithoutAnImdbIdIsNotScrobbled() {
        val r = Rig()
        r.progress.begin()
        val blank = movieRequest.copy(imdb = "")
        r.progress.started(blank)
        r.progress.finish(blank, PlaybackSnapshot(3000.0, 6000.0, playing = true))
        assertTrue(r.scrobbles.isEmpty())
        assertEquals(3000.0, r.history.entry("tt1")!!.position) // history still saved
    }

    @Test
    fun watchTimeIsLoggedOnlyWhilePlayingAndCappedPerTick() {
        val r = Rig()
        r.progress.begin()
        r.clock += 15_000
        r.progress.tick(movieRequest, PlaybackSnapshot(15.0, 6000.0, playing = true))
        assertEquals(15.0, r.log.totalSeconds)
        r.clock += 15_000 // paused for this tick: clock moves, nothing is added
        r.progress.tick(movieRequest, PlaybackSnapshot(15.0, 6000.0, playing = false))
        assertEquals(15.0, r.log.totalSeconds)
        r.clock += 3_600_000 // a long stall counts for 20 s at most
        r.progress.finish(movieRequest, PlaybackSnapshot(40.0, 6000.0, playing = true))
        assertEquals(35.0, r.log.totalSeconds)
    }

    @Test
    fun finishingAMovieMovesASavedTitleToWatched() {
        val r = Rig()
        r.library.set(movie, LibraryStatus.PlanToWatch)
        r.progress.begin()
        r.progress.finish(movieRequest, PlaybackSnapshot(5700.0, 6000.0, playing = true))
        assertEquals(LibraryStatus.Watched, r.library.entry("tt1")!!.status)
        assertTrue(r.history.entry("tt1")!!.isTitleWatched)
    }

    @Test
    fun anUnsavedMovieIsNotAddedToTheLibraryByWatchingIt() {
        val r = Rig()
        r.progress.begin()
        r.progress.finish(movieRequest, PlaybackSnapshot(5700.0, 6000.0, playing = true))
        assertNull(r.library.entry("tt1"))
    }

    @Test
    fun finishingAnEpisodeMarksItWatchedThroughForTheShow() {
        val r = Rig()
        r.progress.begin()
        r.progress.finish(episodeRequest, PlaybackSnapshot(2300.0, 2400.0, playing = true))
        assertEquals(1, r.history.entry("tt2")!!.watchedThrough!!.season)
        assertEquals(3, r.history.entry("tt2")!!.watchedThrough!!.episode)
    }
}

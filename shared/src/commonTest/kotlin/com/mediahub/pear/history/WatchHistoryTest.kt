package com.mediahub.pear.history

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.MetaPreview
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val movie = MetaPreview("tt1", "movie", "A Movie")
private val show = MetaPreview("tt2", "series", "A Show")

private fun history(prefs: PrefStore = PrefStore(), t: () -> Long = { 1_000L }) = WatchHistory(prefs, now = t)

class WatchHistoryProgressTest {
    @Test
    fun aMoviePlayedHalfwayIsInProgressAndOneAlmostFinishedIsWatched() {
        val h = history()
        h.update(movie, "w", 3000.0, 6000.0)
        assertEquals(listOf("tt1"), h.continueWatching.map { it.id })
        assertFalse(h.entry("tt1")!!.isTitleWatched)

        h.update(movie, "w", 5600.0, 6000.0) // 93%
        val e = h.entry("tt1")!!
        assertTrue(e.isFinished)
        assertEquals(true, e.done)
        assertTrue(e.isTitleWatched)
        assertFalse(e.isInProgress)
        assertTrue(h.continueWatching.isEmpty())
    }

    @Test
    fun theFirstThirtySecondsDoNotCountAsInProgress() {
        val h = history()
        h.update(movie, "w", 30.0, 6000.0)
        assertTrue(h.continueEntries.isEmpty())
        h.update(movie, "w", 31.0, 6000.0)
        assertEquals(1, h.continueEntries.size)
    }

    @Test
    fun finishingAnEpisodeSetsTheMarkerAndPlayingTheNextOnePartwayKeepsIt() {
        val h = history()
        h.update(show, "1:3", 2300.0, 2400.0, season = 1, episode = 3)
        assertEquals(SE(1, 3), h.entry("tt2")!!.watchedThrough)
        assertTrue(h.entry("tt2")!!.isUpNextCandidate)

        h.update(show, "1:4", 900.0, 2400.0, season = 1, episode = 4)
        val e = h.entry("tt2")!!
        assertEquals(SE(1, 3), e.watchedThrough) // playing partway never clears it
        assertTrue(e.isInProgress)
        assertFalse(e.isUpNextCandidate) // being watched: no Up Next card
    }

    @Test
    fun theMarkerIsOnlyEverRaisedByFinishing() {
        val h = history()
        h.update(show, "1:5", 2300.0, 2400.0, season = 1, episode = 5)
        h.update(show, "1:3", 2300.0, 2400.0, season = 1, episode = 3)
        assertEquals(SE(1, 5), h.entry("tt2")!!.watchedThrough)
    }

    @Test
    fun seasonAndEpisodeFallBackToTheKey() {
        val h = history()
        h.update(show, "2:7", 2300.0, 2400.0)
        assertEquals(SE(2, 7), h.entry("tt2")!!.seasonEpisode)
        assertEquals(SE(2, 7), h.entry("tt2")!!.watchedThrough)
        h.update(movie, "watched", 10.0, 20.0)
        assertNull(h.entry("tt1")!!.seasonEpisode)
    }

    @Test
    fun theMostRecentTitleComesFirstAndRemembersWhatWasPlayed() {
        val h = history()
        h.update(movie, "w", 100.0, 6000.0)
        h.update(show, "1:1", 100.0, 2400.0, season = 1, episode = 1, episodeTitle = "Pilot", thumb = "https://t/x.jpg")
        assertEquals("tt2", h.lastWatched?.id)
        assertEquals("Pilot", h.entry("tt2")!!.episodeTitle)
        assertEquals("https://t/x.jpg", h.entry("tt2")!!.thumb)
        assertEquals(listOf("tt2", "tt1"), h.entries.map { it.id })
    }

    @Test
    fun progressIsClampedAndZeroWithoutADuration() {
        val h = history()
        h.update(movie, "w", 7000.0, 6000.0)
        assertEquals(1.0, h.entry("tt1")!!.progress)
        h.update(show, "1:1", 5.0, 0.0)
        assertEquals(0.0, h.entry("tt2")!!.progress)
    }
}

class WatchHistoryMarkTest {
    @Test
    fun markThroughCreatesTheEntryAndNeverLowersAMarker() {
        val h = history()
        h.markThrough(5, 1, show, 2400.0)
        assertEquals(SE(1, 5), h.entry("tt2")!!.watchedThrough)
        h.markThrough(3, 1, show, 2400.0)
        assertEquals(SE(1, 5), h.entry("tt2")!!.watchedThrough)
        assertTrue(h.isWatched(1, 4, "tt2"))
        assertFalse(h.isWatched(1, 6, "tt2"))
    }

    @Test
    fun markingWhileAnEpisodeIsInProgressKeepsThatProgressRecord() {
        val h = history()
        h.update(show, "1:7", 900.0, 2400.0, season = 1, episode = 7)
        h.markThrough(5, 1, show, 2400.0)
        val e = h.entry("tt2")!!
        assertEquals(SE(1, 5), e.watchedThrough)
        assertEquals("1:7", e.key)
        assertEquals(900.0, e.position)
    }

    @Test
    fun unmarkingPullsTheMarkerBack() {
        val h = history()
        h.markThrough(5, 1, show, 2400.0)
        h.unmarkThrough(5, 1, show)
        assertEquals(SE(1, 4), h.entry("tt2")!!.watchedThrough)
        h.unmarkThrough(1, 1, show)
        assertNull(h.entry("tt2")!!.watchedThrough) // season 1 episode 1 clears it
        assertEquals(0, h.entry("tt2")!!.wS)

        h.markThrough(3, 2, show, 2400.0)
        h.unmarkThrough(1, 2, show)
        assertEquals(SE(2, 0), h.entry("tt2")!!.watchedThrough) // episode 1 of a later season: "season 1 done"
        h.unmarkThrough(2, 3, show) // above the marker: nothing to pull back
        assertEquals(SE(2, 0), h.entry("tt2")!!.watchedThrough)
    }

    @Test
    fun markWatchedForAMovieMakesAFinishedEntryOfAtLeastAMinute() {
        val h = history()
        h.markWatched(movie)
        val e = h.entry("tt1")!!
        assertTrue(e.isTitleWatched)
        assertEquals(60.0, e.duration)
        assertEquals(60.0, e.position)
    }

    @Test
    fun markWatchedForAShowStartsAtEpisodeOneWhenNothingIsKnown() {
        val h = history()
        h.markWatched(show)
        assertEquals(SE(1, 1), h.entry("tt2")!!.watchedThrough)
        h.markWatched(show, season = 2, episode = 4)
        assertEquals(SE(2, 4), h.entry("tt2")!!.watchedThrough)
    }

    @Test
    fun unmarkWatchedClearsTheStateButKeepsTheRecord() {
        val h = history()
        h.update(movie, "w", 5700.0, 6000.0)
        h.unmarkWatched("tt1")
        val e = h.entry("tt1")!!
        assertFalse(e.isTitleWatched)
        assertEquals(0.0, e.position) // a finished entry is reset so it isn't "watched" again by position
        h.update(show, "1:2", 2300.0, 2400.0, season = 1, episode = 2)
        h.unmarkWatched("tt2")
        assertNull(h.entry("tt2")!!.watchedThrough)
        // The cleared marker survives the next partial play.
        h.update(show, "1:3", 900.0, 2400.0, season = 1, episode = 3)
        assertNull(h.entry("tt2")!!.watchedThrough)
        assertEquals(0, h.entry("tt2")!!.wS)
    }

    @Test
    fun theUpNextCardIsDismissedUntilAnotherEpisodeIsFinished() {
        val h = history()
        h.update(show, "1:3", 2300.0, 2400.0, season = 1, episode = 3)
        assertEquals(setOf("tt2"), h.finishedSeries)
        h.dismissUpNext("tt2")
        assertTrue(h.finishedEntries.isEmpty())
        assertEquals(SE(1, 3), h.entry("tt2")!!.watchedThrough) // the marker stays
        h.update(show, "1:4", 2300.0, 2400.0, season = 1, episode = 4)
        assertEquals(setOf("tt2"), h.finishedSeries)
    }

    @Test
    fun isWatchedUsesTheHintedShowWhenNoIdIsGiven() {
        val h = history()
        h.markThrough(2, 1, show, 2400.0)
        assertFalse(h.isWatched(1, 1))
        h.lastWatchedSeriesId = "tt2"
        assertTrue(h.isWatched(1, 1))
    }
}

class WatchHistoryListTest {
    @Test
    fun removingAMovieKeepsItInTheArchiveAndAShowWithAMarkerStaysWithNoProgress() {
        val h = history()
        h.update(movie, "w", 3000.0, 6000.0)
        h.remove("tt1")
        assertTrue(h.entries.isEmpty())
        assertNotNull(h.entry("tt1")) // statistics still find it
        assertEquals(listOf("tt1"), h.allEntries.map { it.id })

        h.update(show, "1:3", 2300.0, 2400.0, season = 1, episode = 3)
        h.remove("tt2")
        val e = h.entries.single()
        assertEquals(0.0, e.position)
        assertEquals(SE(1, 3), e.watchedThrough)
    }

    @Test
    fun playingAgainBringsAnArchivedTitleBack() {
        val h = history()
        h.update(movie, "w", 3000.0, 6000.0)
        h.remove("tt1")
        h.update(movie, "w", 100.0, 6000.0)
        assertEquals(1, h.entries.size)
        assertTrue(h.archive.isEmpty())
    }

    @Test
    fun theListIsCappedAtThirtyAndTheOldestFinishedTitleGoesToTheArchive() {
        val h = history()
        for (i in 1..31) h.update(MetaPreview("tt$i", "movie", "M$i"), "w", 6000.0, 6000.0)
        assertEquals(30, h.entries.size)
        assertEquals(listOf("tt1"), h.archive.map { it.id })
        assertEquals(31, h.allEntries.size)
    }

    @Test
    fun aTitleInProgressOrWithAMarkerIsNeverPushedOut() {
        val h = history()
        h.update(MetaPreview("keep-a", "movie", "A"), "w", 3000.0, 6000.0)
        h.update(MetaPreview("keep-b", "series", "B"), "1:1", 2300.0, 2400.0, season = 1, episode = 1)
        for (i in 1..35) h.update(MetaPreview("tt$i", "movie", "M$i"), "w", 6000.0, 6000.0)
        val ids = h.entries.map { it.id }
        assertTrue("keep-a" in ids)
        assertTrue("keep-b" in ids)
        assertEquals(30, ids.size)
        assertEquals(7, h.archive.size)
    }

    @Test
    fun linkingMergesTwoEntriesOfTheSameTitleAndKeepsTheFurthestMarker() {
        val h = history()
        h.update(MetaPreview("tt1", "series", "S"), "1:2", 2300.0, 2400.0, season = 1, episode = 2)
        h.update(MetaPreview("tmdb:5", "series", "S"), "1:4", 2300.0, 2400.0, season = 1, episode = 4)
        h.link("tt1", "tmdb:5")
        val e = h.entries.single()
        assertEquals(SE(1, 4), e.watchedThrough)
        assertEquals("tt1", e.alias)
        assertEquals(e, h.entry("tt1"))
        assertEquals(e, h.entry("tmdb:5"))
    }

    @Test
    fun linkingAnEntryWithAMissingOtherSideJustRecordsTheAlias() {
        val h = history()
        h.update(MetaPreview("tt1", "series", "S"), "1:2", 100.0, 2400.0, season = 1, episode = 2)
        h.link("tt1", "tmdb:9")
        assertEquals("tmdb:9", h.entries.single().alias)
        assertNotNull(h.entry("tmdb:9"))
        h.link("tt1", "tt1") // same id: nothing to do
        h.link("tt1", "tmdb:10") // already has an alias: left alone
        assertEquals("tmdb:9", h.entries.single().alias)
    }

    @Test
    fun anAliasLetsLaterPlaysOfTheOtherIdReplaceTheSameEntry() {
        val h = history()
        h.update(MetaPreview("tt1", "movie", "M"), "w", 100.0, 6000.0)
        h.link("tt1", "tmdb:9")
        h.update(MetaPreview("tmdb:9", "movie", "M"), "w", 200.0, 6000.0)
        assertEquals(1, h.entries.size)
        assertEquals(200.0, h.entries.single().position)
    }
}

class WatchHistoryStorageTest {
    @Test
    fun aCheckpointIsSavedWithoutTouchingTheObservedList() {
        val prefs = PrefStore()
        val h = history(prefs)
        h.update(movie, "w", 100.0, 6000.0)
        h.checkpoint(movie, "w", 2000.0, 6000.0)
        assertEquals(100.0, h.entries.single().position)
        assertEquals(2000.0, history(prefs).entries.single().position)
        h.update(movie, "w", 2500.0, 6000.0) // the save at close brings the list up to date
        assertEquals(2500.0, h.entries.single().position)
    }

    @Test
    fun aCheckpointForAnUnknownTitleBehavesLikeAnUpdate() {
        val h = history()
        h.checkpoint(movie, "w", 100.0, 6000.0)
        assertEquals(1, h.entries.size)
    }

    @Test
    fun historySurvivesARestartAndEachProfileHasItsOwn() {
        val prefs = PrefStore()
        val h = history(prefs)
        h.update(movie, "w", 100.0, 6000.0)
        h.load("p2")
        assertTrue(h.entries.isEmpty())
        h.update(show, "1:1", 100.0, 2400.0, season = 1, episode = 1)
        assertNotNull(prefs.jsonText("watch.history"))
        assertNotNull(prefs.jsonText("watch.history.p2"))
        h.load("default")
        assertEquals(listOf("tt1"), h.entries.map { it.id })
        assertEquals(listOf("tt1"), history(prefs).entries.map { it.id })
        assertEquals(listOf("tt2"), WatchHistory(prefs, "p2").entries.map { it.id })
    }

    @Test
    fun theArchiveIsSavedUnderItsOwnKey() {
        val prefs = PrefStore()
        val h = history(prefs)
        h.update(movie, "w", 100.0, 6000.0)
        h.remove("tt1")
        assertNotNull(prefs.jsonText("watch.archive"))
        assertEquals(listOf("tt1"), history(prefs).archive.map { it.id })
    }

    @Test
    fun reloadFollowsWhatIsInStorage() {
        val prefs = PrefStore()
        val h = history(prefs)
        h.update(movie, "w", 100.0, 6000.0)
        prefs.setJsonText("watch.history", "[]")
        h.reload()
        assertTrue(h.entries.isEmpty())
    }
}

class HistoryJsonTest {
    private val swift =
        """[{"item":{"id":"tt1","type":"series","name":"A","releaseInfo":"2020"},"key":"1:2","position":2300,"duration":2400,"updated":781234567.5,"season":1,"episode":2,"wS":1,"wE":2,"alias":"tmdb:5","hS":1,"hE":1}]"""

    @Test
    fun theIosJsonIsReadWithItsDateConverted() {
        val e = readEntries(swift).single()
        assertEquals("tt1", e.item.id)
        assertEquals("A", e.item.name)
        assertEquals(1_759_541_767_500L, e.updated) // Swift dates count seconds from 2001-01-01
        assertEquals(SE(1, 2), e.watchedThrough)
        assertEquals("tmdb:5", e.alias)
        assertEquals(1, e.hS)
    }

    @Test
    fun writingKeepsTheSwiftShape() {
        val e = readEntries(swift).single()
        val text = PearJson.encodeToString(ListSerializer(HistoryEntry.serializer()), listOf(e))
        assertTrue("\"updated\":781234567.5" in text)
        assertTrue("\"wS\":1" in text)
        assertTrue("\"done\"" !in text) // nothing is written for a missing value
        assertEquals(e, readEntries(text).single())
    }

    @Test
    fun anUnreadableEntryIsSkippedNotTheWholeHistory() {
        val text = """[{"nope":1},{"item":{"id":"tt1","type":"movie","name":"A"},"key":"k","position":1,"duration":2,"updated":0}]"""
        assertEquals(listOf("tt1"), readEntries(text).map { it.id })
        assertTrue(readEntries("not json").isEmpty())
        assertTrue(readEntries(null).isEmpty())
        assertEquals(978_307_200_000L, readEntries(text).single().updated)
    }
}

class FmtTest {
    @Test
    fun clock() {
        assertEquals("1:23", Fmt.clock(83.0))
        assertEquals("1:02:05", Fmt.clock(3725.0))
        assertEquals("0:00", Fmt.clock(-1.0))
        assertEquals("0:00", Fmt.clock(Double.NaN))
    }

    @Test
    fun remaining() {
        assertEquals("41 min left", Fmt.remaining(2460.0))
        assertEquals("1h 15m left", Fmt.remaining(4500.0))
        assertEquals("1h left", Fmt.remaining(3600.0))
        assertEquals("1 min left", Fmt.remaining(0.0))
    }
}

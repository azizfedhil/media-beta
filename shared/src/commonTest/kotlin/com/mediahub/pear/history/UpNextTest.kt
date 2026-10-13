package com.mediahub.pear.history

import com.mediahub.pear.model.MetaPreview
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TODAY = "2026-10-11"
private val show = MetaPreview("tt2", "series", "A Show", background = "https://img/bg.jpg")

private fun eps(vararg ids: Int) = ids.map { EpisodeItem(it, "Episode $it", airDate = "2020-01-01") }

class EpisodeItemTest {
    @Test
    fun anAirDateAfterTodayIsUpcomingAndUnknownDatesCountAsAired() {
        assertTrue(EpisodeItem(1, "a", airDate = "2026-10-12").isUpcoming(TODAY))
        assertFalse(EpisodeItem(1, "a", airDate = TODAY).isUpcoming(TODAY))
        assertFalse(EpisodeItem(1, "a", airDate = null).isUpcoming(TODAY))
        assertFalse(EpisodeItem(1, "a", airDate = "").isUpcoming(TODAY))
    }

    private fun rated(vararg r: Double, votes: Int? = null) =
        r.mapIndexed { i, x -> EpisodeItem(i + 1, "E${i + 1}", rating = x, votes = votes, airDate = "2020-01-01") }

    @Test
    fun theBestRatedEpisodesOfASeasonAreFlagged() {
        val out = EpisodeItem.markTopRated(rated(7.0, 8.5, 8.5, 6.0, 7.5), TODAY)
        assertEquals(listOf(2, 3), out.filter { it.isTopRated }.map { it.id })
    }

    @Test
    fun fewerThanThreeRatedOrAllEqualFlagsNothing() {
        assertTrue(EpisodeItem.markTopRated(rated(9.0, 8.0), TODAY).none { it.isTopRated })
        assertTrue(EpisodeItem.markTopRated(rated(8.0, 8.0, 8.0), TODAY).none { it.isTopRated })
    }

    @Test
    fun upcomingUnratedAndFewVoteEpisodesDoNotCount() {
        val list = rated(7.0, 7.5, 8.0) + EpisodeItem(4, "E4", rating = 9.9, airDate = "2026-12-01") + EpisodeItem(5, "E5", rating = 0.0, airDate = "2020-01-01")
        val out = EpisodeItem.markTopRated(list, TODAY)
        assertEquals(listOf(3), out.filter { it.isTopRated }.map { it.id })
        assertTrue(EpisodeItem.markTopRated(rated(7.0, 7.5, 9.0, votes = 2), TODAY).none { it.isTopRated }) // too few votes behind each score
    }

    @Test
    fun anOldFlagIsClearedWhenItNoLongerHolds() {
        val stale = rated(7.0, 8.0, 9.0).map { it.copy(isTopRated = true) }
        assertEquals(listOf(3), EpisodeItem.markTopRated(stale, TODAY).filter { it.isTopRated }.map { it.id })
    }
}

class UpNextTest {
    private val seasons = mapOf(1 to eps(1, 2, 3, 4, 5), 2 to eps(1, 2), 3 to eps(1, 2).map { it.copy(airDate = "2026-12-24") })
    private val load: suspend (Int) -> List<EpisodeItem> = { s -> seasons[s].orEmpty() }

    @Test
    fun theNextEpisodeOfTheSeasonComesFirst() = runTest {
        val n = UpNext.next(1, 2, load, TODAY)!!
        assertEquals(1 to 3, n.first to n.second.id)
    }

    @Test
    fun gapsInTheNumberingAreSkipped() = runTest {
        val n = UpNext.next(1, 2, { eps(1, 2, 4) }, TODAY)!!
        assertEquals(4, n.second.id)
    }

    @Test
    fun afterTheLastEpisodeItIsEpisodeOneOfTheNextSeason() = runTest {
        val n = UpNext.next(1, 5, load, TODAY)!!
        assertEquals(2 to 1, n.first to n.second.id)
    }

    @Test
    fun nothingFurtherAfterTheFinaleOrWhenTheNextSeasonHasNotAired() = runTest {
        assertNull(UpNext.next(2, 2, load, TODAY)) // season 3 is announced for December
        assertNull(UpNext.next(3, 2, load, TODAY))
    }

    @Test
    fun anUpcomingEpisodeIsNotOffered() = runTest {
        val n = UpNext.next(1, 2, { listOf(EpisodeItem(1, "a", airDate = "2020-01-01"), EpisodeItem(2, "b", airDate = "2020-01-01"), EpisodeItem(3, "c", airDate = "2027-01-01")) }, TODAY)
        assertNull(n)
    }

    @Test
    fun withNoMetadataTheFollowingEpisodeIsAssumed() = runTest {
        val n = UpNext.next(1, 4, { emptyList() }, TODAY)!!
        assertEquals(1 to 5, n.first to n.second.id)
        assertEquals("Episode 5", n.second.name)
    }

    private fun finished(season: Int, episode: Int) =
        HistoryEntry(show, "$season:$episode", 2300.0, 2400.0, 777L, season = season, episode = episode, wS = season, wE = episode)

    @Test
    fun resolveBuildsTheCardFromTheMarkerAndFallsBackToTheShowBackdrop() = runTest {
        val u = UpNext.resolve(finished(1, 5), load, TODAY)!!
        assertEquals("tt2:2:1", u.id)
        assertEquals("Episode 1", u.title)
        assertEquals(777L, u.updated)
        assertEquals("https://img/bg.jpg", u.thumb) // the episode has no still
        val withStill = UpNext.resolve(finished(1, 2), { eps(1, 2, 3).map { it.copy(image = "https://img/s.jpg", runtime = 42) } }, TODAY)!!
        assertEquals("https://img/s.jpg", withStill.thumb)
        assertEquals(42, withStill.runtime)
    }

    @Test
    fun resolveUsesTheMarkerNotTheEpisodeLastPlayed() = runTest {
        val e = finished(1, 2).copy(season = 1, episode = 2, wS = 1, wE = 4) // marked through episode 4
        assertEquals(5, UpNext.resolve(e, load, TODAY)!!.episode)
    }

    @Test
    fun resolveFallsBackToTheKeyAndGivesUpWithoutNumbers() = runTest {
        val noMarker = HistoryEntry(show, "1:3", 100.0, 2400.0, 0L)
        assertEquals(4, UpNext.resolve(noMarker, load, TODAY)!!.episode)
        assertNull(UpNext.resolve(HistoryEntry(show, "watched", 100.0, 2400.0, 0L), load, TODAY))
    }
}

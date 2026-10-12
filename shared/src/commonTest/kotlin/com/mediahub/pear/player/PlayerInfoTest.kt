package com.mediahub.pear.player

import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.EpisodeInfo
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.TmdbDetails
import com.mediahub.pear.player.ui.PlayerInfoRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerInfoTest {
    private val series = MetaPreview(id = "tt1", type = "series", name = "Show", description = "The series synopsis", releaseInfo = "2020-")
    private val film = MetaPreview(id = "tt2", type = "movie", name = "Film", description = "The film synopsis", releaseInfo = "2019", rating = 7.0)

    private fun episodeRequest() = PlayRequest(
        url = "https://x.example/v.mkv",
        item = series,
        key = "2:6",
        imdb = "tt1",
        season = 2,
        episode = 6,
        episodeTitle = null,
        thumb = "https://img.example/still.jpg",
    )

    private fun movieRequest() = PlayRequest(url = "https://x.example/f.mkv", item = film, key = "movie", imdb = "tt2")

    @Test
    fun longDateReadsCalendarDatesOnly() {
        assertEquals("June 12, 2025", PlayerInfoRules.longDate("2025-06-12"))
        assertEquals("February 29, 2024", PlayerInfoRules.longDate("2024-02-29"))
        assertNull(PlayerInfoRules.longDate("2025-02-29"))
        assertNull(PlayerInfoRules.longDate("2025-13-01"))
        assertNull(PlayerInfoRules.longDate("2025"))
        assertNull(PlayerInfoRules.longDate(""))
        assertNull(PlayerInfoRules.longDate(null))
    }

    @Test
    fun runtimeTextMatchesTheSwiftFormat() {
        assertEquals("1h 45m", PlayerInfoRules.runtimeText(105))
        assertEquals("2h", PlayerInfoRules.runtimeText(120))
        assertEquals("45 min", PlayerInfoRules.runtimeText(45))
        assertNull(PlayerInfoRules.runtimeText(0))
        assertNull(PlayerInfoRules.runtimeText(null))
    }

    @Test
    fun basicEpisodeCardIsLoadingAndTaggedWithSeasonAndEpisode() {
        val info = PlayerInfoRules.basic(episodeRequest())
        assertTrue(info.isLoading)
        assertEquals("S2 · E6", info.episodeTag)
        assertEquals("Show", info.kicker)
        assertEquals("Episode 6", info.title)
        assertEquals("https://img.example/still.jpg", info.image)
    }

    @Test
    fun episodeWithoutSynopsisBorrowsTheSeriesAndSaysSo() {
        val ep = EpisodeInfo(id = 1, episodeNumber = 6, name = "Title", overview = null)
        val info = PlayerInfoRules.episodeCard(episodeRequest(), ep)
        assertEquals("The series synopsis", info.overview)
        assertEquals("About the series", info.overviewNote)
        assertFalse(info.isLoading)
    }

    @Test
    fun episodeWithItsOwnSynopsisKeepsIt() {
        val ep = EpisodeInfo(
            id = 1,
            episodeNumber = 6,
            name = "Title",
            overview = "Its own",
            airDate = "2025-06-12",
            runtime = 44,
            voteAverage = 7.46,
        )
        val info = PlayerInfoRules.episodeCard(episodeRequest(), ep)
        assertEquals("Its own", info.overview)
        assertNull(info.overviewNote)
        assertEquals("June 12, 2025", info.date)
        assertEquals(44, info.runtime)
        assertEquals(listOf("TMDB"), info.ratings.map { it.label })
        assertEquals("7.5", info.ratings.single().text)
    }

    @Test
    fun missingEpisodeStillGivesACard() {
        val info = PlayerInfoRules.episodeCard(episodeRequest(), null)
        assertEquals("Episode 6", info.title)
        assertEquals("The series synopsis", info.overview)
        assertFalse(info.isLoading)
    }

    @Test
    fun movieRatingsKeepTheFirstOfEachSourceAndAddTmdbAndTheCatalogScore() {
        val mdb = listOf(MdbRating("IMDb", "7.1", 7.1))
        val details = TmdbDetails(overview = "From TMDB", releaseDate = "2019-03-04", runtime = 95, voteAverage = 6.84)
        val info = PlayerInfoRules.movieCard(movieRequest(), details, mdb)
        assertEquals(listOf("IMDb", "TMDB"), info.ratings.map { it.label })
        assertEquals("7.1", info.ratings.first().text)
        assertEquals("6.8", info.ratings.last().text)
        assertEquals("From TMDB", info.overview)
        assertEquals("March 4, 2019", info.date)
        assertEquals(95, info.runtime)
    }

    @Test
    fun movieWithoutTmdbFallsBackToTheCatalog() {
        val info = PlayerInfoRules.movieCard(movieRequest(), details = null, mdb = emptyList())
        assertEquals("The film synopsis", info.overview)
        assertEquals("2019", info.date)
        assertEquals(listOf("IMDb"), info.ratings.map { it.label })
        assertEquals("7.0", info.ratings.single().text)
    }

    @Test
    fun oneDecimalRoundsToTenths() {
        assertEquals("7.5", PlayerInfoRules.oneDecimal(7.46))
        assertEquals("10.0", PlayerInfoRules.oneDecimal(10.0))
    }
}

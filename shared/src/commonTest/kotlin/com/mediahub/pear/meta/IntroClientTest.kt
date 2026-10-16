package com.mediahub.pear.meta

import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val movie = MetaPreview("tt1", "movie", "A Movie")
private val show = MetaPreview("tt2", "series", "A Show")

private const val BASE = "https://api.theintrodb.org/v3/media?"

private const val FULL =
    """{"intro":[{"start_ms":1000,"end_ms":61000}],"credits":[{"start_ms":3000000,"end_ms":null}],"recap":[{"start_ms":null,"end_ms":2000}],"preview":[{"start_ms":10000,"end_ms":20000}]}"""

private fun client(http: FakeHttp, tmdb: Int? = 603, enabled: Boolean = true) =
    IntroClient(http, enabled = { enabled }, tmdbId = { _, _ -> tmdb }, retryDelayMillis = 0)

class IntroClientTest {
    @Test
    fun segmentsAreReadSortedAndShortOnesDropped() = runTest {
        val http = FakeHttp { reply(FULL) }
        val got = client(http).segments(movie, "tt1", null, null)
        // The 2 s recap is too short to offer. A missing end runs to the end of the media.
        assertEquals(
            listOf(
                SkipSegment(SkipSegment.Kind.Intro, 1.0, 61.0),
                SkipSegment(SkipSegment.Kind.Preview, 10.0, 20.0),
                SkipSegment(SkipSegment.Kind.Credits, 3000.0, null),
            ).sortedBy { it.start },
            got,
        )
        assertEquals(BASE + "tmdb_id=603", http.requests.single().url)
    }

    @Test
    fun anEpisodeAsksWithSeasonAndEpisode() = runTest {
        val http = FakeHttp { reply(FULL) }
        client(http).segments(show, "tt2", 1, 2)
        assertEquals(BASE + "tmdb_id=603&season=1&episode=2", http.requests.single().url)
    }

    @Test
    fun aMissingStartMeansTheBeginning() = runTest {
        val http = FakeHttp { reply("""{"recap":[{"end_ms":9000}]}""") }
        val got = client(http).segments(show, "tt2", 1, 2)
        assertEquals(listOf(SkipSegment(SkipSegment.Kind.Recap, 0.0, 9.0)), got)
        assertEquals("Skip Recap", got.single().label)
    }

    @Test
    fun labelsAndIds() {
        assertEquals("Skip Intro", SkipSegment(SkipSegment.Kind.Intro, 12.7, 60.0).label)
        assertEquals("Skip Credits", SkipSegment(SkipSegment.Kind.Credits, 1.0, null).label)
        assertEquals("Skip Preview", SkipSegment(SkipSegment.Kind.Preview, 1.0, null).label)
        assertEquals("intro-12", SkipSegment(SkipSegment.Kind.Intro, 12.7, 60.0).id)
    }

    @Test
    fun theImdbIdIsTriedWhenTheTmdbIdIsUnknownToTheService() = runTest {
        val http = FakeHttp { r -> if ("tmdb_id" in r.url) reply("{}", 404) else reply(FULL) }
        val c = client(http)
        assertEquals(3, c.segments(movie, "tt1", null, null).size)
        assertEquals(listOf(BASE + "tmdb_id=603", BASE + "imdb_id=tt1"), http.requests.map { it.url })
        c.segments(movie, "tt1", null, null) // kept for the run
        assertEquals(2, http.requests.size)
    }

    @Test
    fun anAnswerOfNothingIsRememberedButAFailureIsNot() = runTest {
        val none = FakeHttp { reply("{}", 404) }
        val c = client(none)
        assertTrue(c.segments(movie, "tt1", null, null).isEmpty())
        val asked = none.requests.size
        assertTrue(c.segments(movie, "tt1", null, null).isEmpty())
        assertEquals(asked, none.requests.size)

        val down = FakeHttp { reply("", 503) }
        val d = client(down)
        assertTrue(d.segments(movie, "tt1", null, null).isEmpty())
        assertEquals(4, down.requests.size) // two ids, two tries each
        d.segments(movie, "tt1", null, null)
        assertEquals(8, down.requests.size)
    }

    @Test
    fun anEmptyOrUnreadableAnswerCountsAsNothing() = runTest {
        assertTrue(client(FakeHttp { reply("""{"intro":[]}""") }).segments(movie, "tt1", null, null).isEmpty())
        assertTrue(client(FakeHttp { reply("not json") }).segments(movie, "tt1", null, null).isEmpty())
    }

    @Test
    fun switchedOffOrWithoutAnyIdNothingIsAsked() = runTest {
        val http = FakeHttp { reply(FULL) }
        assertTrue(client(http, enabled = false).segments(movie, "tt1", null, null).isEmpty())
        assertTrue(client(http, tmdb = null).segments(MetaPreview("kitsu:1", "series", "K"), "kitsu:1", 1, 1).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun withoutATmdbIdTheImdbIdIsUsed() = runTest {
        val http = FakeHttp { reply(FULL) }
        client(http, tmdb = null).segments(show, "tt2", 3, 4)
        assertEquals(BASE + "imdb_id=tt2&season=3&episode=4", http.requests.single().url)
    }
}

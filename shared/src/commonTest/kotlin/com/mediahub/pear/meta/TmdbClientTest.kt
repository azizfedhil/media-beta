package com.mediahub.pear.meta

import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.model.ArtworkSink
import com.mediahub.pear.model.DiscoverSort
import com.mediahub.pear.model.RawArtwork
import com.mediahub.pear.net.BadStatusException
import com.mediahub.pear.net.FactStore
import com.mediahub.pear.net.MemoryFactStore
import com.mediahub.pear.net.MemoryResponseCache
import com.mediahub.pear.net.NetworkException
import com.mediahub.pear.net.NoKeyException
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.net.UnresolvableException
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.param
import com.mediahub.pear.testing.reply
import com.mediahub.pear.testing.tmdbPath
import com.mediahub.pear.testing.tmdbRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ITEMS = """{"page":1,"results":[
  {"id":603,"title":"The Matrix","poster_path":"/m.jpg","backdrop_path":"/b.jpg","release_date":"1999-03-30","vote_average":8.2,"genre_ids":[28,878],"original_language":"en","overview":"Neo."},
  {"id":1,"name":"A Show","first_air_date":"2020-01-02","vote_average":0,"origin_country":["JP"],"original_language":"ja"}]}"""

private const val FIND = """{"movie_results":[{"id":603}],"tv_results":[{"id":77}]}"""

private fun client(
    http: FakeHttp,
    key: String = "",
    cache: ResponseCache = ResponseCache.None,
    facts: FactStore = FactStore.None,
    now: () -> Long = { 0L },
    proxyBase: String = TmdbEndpoints.PROXY_BASE,
) = TmdbClient(
    http = http, apiKey = { key }, cache = cache, facts = facts, proxyBase = proxyBase,
    now = now, today = { "2026-10-10" }, persistDelayMillis = 600_000,
)

class TmdbClientTest {
    @Test
    fun withoutAKeyRequestsGoThroughTheProxyWithThePublicKeyInHeaders() = runTest {
        val http = tmdbRouter("/trending/tv/week" to ITEMS)
        val items = client(http).trending("tv", page = 2)
        val r = http.requests.single()
        assertTrue(r.url.startsWith("${TmdbEndpoints.PROXY_BASE}/trending/tv/week?"), r.url)
        assertEquals("Bearer ${TmdbEndpoints.ANON_KEY}", r.headers["Authorization"])
        assertEquals(TmdbEndpoints.ANON_KEY, r.headers["apikey"])
        assertNull(r.param("api_key"))
        assertEquals("2", r.param("page"))

        val show = items[1]
        assertEquals("tmdb:1", show.id)
        assertEquals("series", show.type)
        assertEquals("A Show", show.name)
        assertEquals("2020", show.releaseInfo)
        assertNull(show.rating) // a vote average of 0 means "none"
        assertEquals(listOf("JP"), show.countries)
        assertEquals("ja", show.language)
    }

    @Test
    fun aKeyOfTheUsersOwnGoesStraightToTmdbAndBypassesTheProxy() = runTest {
        val http = tmdbRouter("/trending/movie/week" to ITEMS)
        val c = client(http, key = " abc ")
        assertTrue(c.usesOwnKey)
        val movie = c.trending("movie").first()
        val r = http.requests.single()
        assertTrue(r.url.startsWith("https://api.themoviedb.org/3/trending/movie/week?"), r.url)
        assertEquals("abc", r.param("api_key"))
        assertTrue(r.headers.isEmpty())
        assertEquals("tmdb:603", movie.id)
        assertEquals("movie", movie.type)
        assertEquals("https://image.tmdb.org/t/p/w342/m.jpg", movie.poster)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", movie.background)
        assertEquals("1999", movie.releaseInfo)
        assertEquals(8.2, movie.rating)
        assertEquals(listOf(28, 878), movie.genreIDs)
        assertEquals("Neo.", movie.description)
    }

    @Test
    fun withNeitherAKeyNorAProxyNothingIsAskedAndTrendingSaysWhy() = runTest {
        val http = tmdbRouter()
        val c = client(http, proxyBase = "")
        assertEquals(false, c.hasKey)
        assertEquals(emptyList(), c.search("x"))
        assertNull(c.logo("tt1", "movie"))
        assertNull(c.tmdbIdentifier("tt1", "movie"))
        assertEquals(42, c.tmdbIdentifier("tmdb:42", "movie"))
        assertFailsWith<NoKeyException> { c.trending("movie") }
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun searchKeepsOnlyMoviesAndShows() = runTest {
        val body = """{"results":[{"id":1,"title":"M","media_type":"movie"},{"id":2,"name":"Someone","media_type":"person"},{"id":3,"name":"S","media_type":"tv"}]}"""
        val http = tmdbRouter("/search/multi" to body)
        val found = client(http).search("the matrix")
        assertEquals(listOf("tmdb:1" to "movie", "tmdb:3" to "series"), found.map { it.id to it.type })
        assertEquals("the matrix", http.requests.single().param("query"))
        assertEquals("false", http.requests.single().param("include_adult"))
        // A failing request is "no results", not an error.
        assertEquals(emptyList(), client(FakeHttp { reply("{}", 500) }).search("x"))
    }

    @Test
    fun resolvingAnImdbIdCostsOneFindRequestEver() = runTest {
        val http = tmdbRouter("/find/tt0133093" to FIND, "/movie/603" to """{"overview":"Neo","runtime":136,"revenue":2923706026,"budget":63000000}""")
        val c = client(http)
        val d = c.details("tt0133093", "movie")
        c.details("tt0133093", "movie")
        assertEquals(1, http.requests.count { it.tmdbPath() == "/find/tt0133093" })
        assertEquals(2, http.requests.count { it.tmdbPath() == "/movie/603" })
        assertEquals("imdb_id", http.requests.first { it.tmdbPath().startsWith("/find") }.param("external_source"))
        assertEquals("credits", http.requests.last().param("append_to_response"))
        assertEquals(2923706026L, d.revenue) // above 2^31: must not be an Int
        assertEquals(136, d.minutes)
    }

    @Test
    fun concurrentAsksForTheSameIdShareOneFindRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = FakeHttp { r ->
            if (r.tmdbPath().startsWith("/find/")) { gate.await(); reply(FIND) } else reply("{}", 404)
        }
        val c = client(http)
        val a = async { c.tmdbIdentifier("tt0133093", "movie") }
        val b = async { c.tmdbIdentifier("tt0133093", "movie") }
        val d = async { c.tmdbIdentifier("tt0133093", "movie") }
        withContext(Dispatchers.Default) { delay(300) } // real time: let all three reach the wait
        gate.complete(Unit)
        assertEquals(603, a.await())
        assertEquals(603, b.await())
        assertEquals(603, d.await())
        assertEquals(1, http.requests.count { it.tmdbPath().startsWith("/find/") })
    }

    @Test
    fun addOnSpecificIdsNeverCostARequest() = runTest {
        val http = tmdbRouter()
        val c = client(http)
        assertNull(c.tmdbIdentifier("kitsu:1", "series"))
        assertNull(c.network("mal:5", "series"))
        assertFailsWith<UnresolvableException> { c.details("yt:abc", "movie") }
        assertEquals(emptyList(), c.episodes("kitsu:1", "series", 1))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun missesAreRememberedButNetworkFailuresAreNot() = runTest {
        val http = tmdbRouter("/find/tt9" to """{"movie_results":[],"tv_results":[]}""")
        val c = client(http)
        assertNull(c.tmdbIdentifier("tt9", "movie"))
        assertNull(c.tmdbIdentifier("tt9", "movie"))
        assertEquals(1, http.requests.size) // the second ask knew already

        var online = false
        val flaky = FakeHttp { if (online) reply(FIND) else throw NetworkException("offline") }
        val f = client(flaky)
        assertNull(f.tmdbIdentifier("tt1", "movie"))
        online = true
        assertEquals(603, f.tmdbIdentifier("tt1", "movie")) // asked again, and now it works
        assertEquals(2, flaky.requests.size)
    }

    @Test
    fun discoverAsksTheSameQuestionsAsTheSwiftClient() = runTest {
        val http = tmdbRouter("/discover/tv" to ITEMS, "/discover/movie" to ITEMS)
        val rules = ContentRules(hiddenCategories = setOf(ContentCategory.Horror, ContentCategory.Kids))
        val c = client(http)
        c.discover("tv", genre = 18, year = 2020, sort = DiscoverSort.Newest, page = 3, rules = rules)
        val n = http.requests.last()
        assertEquals("first_air_date.desc", n.param("sort_by"))
        assertEquals("2026-10-10", n.param("first_air_date.lte"))
        assertEquals("5", n.param("vote_count.gte"))
        assertEquals("27,10751,10762", n.param("without_genres"))
        assertEquals("18", n.param("with_genres"))
        assertEquals("2020", n.param("first_air_date_year"))
        assertEquals("3", n.param("page"))
        assertEquals("false", n.param("include_adult"))

        c.discover("movie", null, 1999, DiscoverSort.TopRated, 1)
        val t = http.requests.last()
        assertEquals("vote_average.desc", t.param("sort_by"))
        assertEquals("500", t.param("vote_count.gte"))
        assertEquals("1999", t.param("primary_release_year"))
        assertNull(t.param("without_genres"))
        assertNull(t.param("with_genres"))

        c.discover("movie", null, null, DiscoverSort.Newest, 1)
        assertEquals("2026-10-10", http.requests.last().param("primary_release_date.lte"))
        c.discover("tv", null, null, DiscoverSort.TopRated, 1)
        assertEquals("200", http.requests.last().param("vote_count.gte"))
    }

    @Test
    fun themedRowsInterleaveShowsAndMoviesAndCarryGenreNames() = runTest {
        fun page(vararg ids: Int, noPoster: Set<Int> = emptySet()): String = "{\"results\":[" + ids.joinToString(",") { id ->
            val poster = if (id in noPoster) "" else "\"poster_path\":\"/p$id.jpg\","
            "{\"id\":$id,\"title\":\"T$id\",$poster\"genre_ids\":[18,10765,99],\"original_language\":\"ko\"}"
        } + "]}"
        val http = tmdbRouter(
            "/search/keyword" to """{"results":[{"id":10,"name":"korean drama"},{"id":11,"name":"other"}]}""",
            "/discover/tv" to page(1, 2),
            "/discover/movie" to page(3, 4, noPoster = setOf(4)),
            "/genre/tv/list" to """{"genres":[{"id":18,"name":"Drama"},{"id":10765,"name":"Sci-Fi"}]}""",
            "/genre/movie/list" to """{"genres":[{"id":18,"name":"Drama"}]}""",
        )
        val c = client(http)
        val out = c.themed(listOf("korean drama"), language = "ko", limit = 8)
        assertEquals(listOf("tmdb:1", "tmdb:3", "tmdb:2"), out.map { it.id }) // 4 has no poster
        assertEquals(listOf("Drama", "Sci-Fi"), out[0].genres)
        assertEquals(listOf("Drama"), out[1].genres)
        val discover = http.requests.first { it.tmdbPath() == "/discover/tv" }
        assertEquals("10", discover.param("with_keywords"))
        assertEquals("ko", discover.param("with_original_language"))
        assertEquals("popularity.desc", discover.param("sort_by"))
        assertEquals("100", discover.param("vote_count.gte"))
        assertEquals("200", http.requests.first { it.tmdbPath() == "/discover/movie" }.param("vote_count.gte"))
        assertEquals(2, c.themed(listOf("korean drama"), language = "ko", limit = 2).size)
    }

    @Test
    fun aThemeIsSkippedWhenItCouldOnlyShowHiddenTitlesOrWhenKeywordsAreUnknown() = runTest {
        val http = tmdbRouter("/search/keyword" to """{"results":[]}""")
        val c = client(http)
        val asianHidden = ContentRules(hiddenCategories = setOf(ContentCategory.Asian))
        assertEquals(emptyList(), c.themed(listOf("korean drama"), "ko", rules = asianHidden))
        assertTrue(http.requests.isEmpty()) // not even the keyword lookup
        assertEquals(emptyList(), c.themed(listOf("nonsense"), "ko")) // unknown keyword: never widen to "everything"
        assertTrue(http.requests.none { it.tmdbPath().startsWith("/discover") })
    }

    @Test
    fun answersAreKeptUntilTheyExpireAndStandInWhenTheServerFails() = runTest {
        var clock = 0L
        var mode = "ok"
        val http = FakeHttp {
            when (mode) { "ok" -> reply(ITEMS); "down" -> throw NetworkException("offline"); "500" -> reply("oops", 503); else -> reply("{}", 401) }
        }
        val c = client(http, cache = MemoryResponseCache(), now = { clock })
        assertEquals(2, c.trending("movie").size)
        clock += 10 * 60_000
        c.trending("movie")
        assertEquals(1, http.requests.size) // fresh: served without asking
        clock += 31 * 60_000
        c.trending("movie")
        assertEquals(2, http.requests.size) // expired: asked again

        clock += 60 * 60_000
        mode = "down"
        assertEquals(2, c.trending("movie").size) // an old answer beats an error
        mode = "500"
        assertEquals(2, c.trending("movie").size)
        mode = "401"
        assertFailsWith<BadStatusException> { c.trending("movie") } // a bad key is never covered up
        // A body that doesn't parse is an error and is not kept.
        val broken = FakeHttp { reply("""{"unexpected":true}""") }
        val b = client(broken, cache = MemoryResponseCache())
        assertFailsWith<Exception> { b.trending("movie") }
        assertFailsWith<Exception> { b.trending("movie") }
        assertEquals(2, broken.requests.size)
    }

    @Test
    fun theKeyIsNeverPartOfWhatIsCached() = runTest {
        val cache = MemoryResponseCache()
        val http = tmdbRouter("/trending/movie/week" to ITEMS)
        client(http, key = "secret", cache = cache).trending("movie")
        client(http, key = "", cache = cache).trending("movie") // another way in, same answer
        assertEquals(1, http.requests.size)
    }

    @Test
    fun permanentFactsSurviveARestart() = runTest {
        val facts = MemoryFactStore()
        val http = tmdbRouter("/movie/603/external_ids" to """{"imdb_id":"tt0133093"}""", "/find/tt0133093" to FIND)
        val first = client(http, facts = facts)
        assertEquals("tt0133093", first.imdbId(603, "movie"))
        assertEquals(603, first.tmdbIdentifier("tt0133093", "movie"))
        first.flushPending()
        assertTrue(facts.files.getValue("tmdb-facts.json").contains("tt0133093"))

        val offline = FakeHttp { throw NetworkException("offline") }
        val second = client(offline, facts = facts)
        assertEquals("tt0133093", second.imdbId(603, "movie"))
        assertEquals(603, second.tmdbIdentifier("tt0133093", "movie"))
        assertTrue(offline.requests.isEmpty())
    }

    @Test
    fun anImdbMissIsNotRemembered() = runTest {
        var imdb = ""
        val http = FakeHttp { reply("""{"imdb_id":${if (imdb.isEmpty()) "null" else "\"$imdb\""}}""") }
        val c = client(http)
        assertNull(c.imdbId(5, "movie"))
        imdb = "tt5"
        assertEquals("tt5", c.imdbId(5, "movie"))
    }

    @Test
    fun theBestLogoIsEnglishThenHighestVotedAndPostersGoToTheArtworkSink() = runTest {
        val images = """{"logos":[
            {"file_path":"/a.svg","iso_639_1":"en","vote_average":9.9,"width":900},
            {"file_path":"/fr.png","iso_639_1":"fr","vote_average":9.9,"width":900},
            {"file_path":"/en5.png","iso_639_1":"en","vote_average":5.0,"width":300},
            {"file_path":"/en7.png","iso_639_1":"en","vote_average":7.0,"width":200},
            {"file_path":"/none9.png","vote_average":9.0,"width":400}],
          "posters":[{"file_path":"/p.jpg","iso_639_1":"en","vote_average":5.5,"width":1000,"height":1500}],
          "backdrops":[{"file_path":"/bd.jpg","vote_average":6.0,"width":1920,"height":1080}]}"""
        val http = tmdbRouter("/movie/5/images" to images)
        val c = client(http)
        var seen: Triple<String, List<RawArtwork>, List<RawArtwork>>? = null
        c.artwork = ArtworkSink { id, p, b -> seen = Triple(id, p, b) }
        assertEquals("https://image.tmdb.org/t/p/w500/en7.png", c.logo("tmdb:5", "movie"))
        assertEquals("en,null", http.requests.single().param("include_image_language"))
        assertEquals("tmdb:5", seen!!.first)
        assertEquals(RawArtwork("/p.jpg", "en", 1000, 1500, 5.5), seen!!.second.single())
        assertEquals(RawArtwork("/bd.jpg", null, 1920, 1080, 6.0), seen!!.third.single())
    }

    @Test
    fun episodesAreMappedAndKept() = runTest {
        val season = """{"episodes":[{"id":1,"name":"Pilot","overview":"o","episode_number":1,"still_path":"/s.jpg","vote_average":7.5,"vote_count":10,"runtime":45,"air_date":"2020-01-01"},{"id":2,"episode_number":2}]}"""
        val http = tmdbRouter("/tv/9/season/1" to season)
        val c = client(http)
        val eps = c.episodes("tmdb:9", "series", 1)
        assertEquals("Pilot", eps[0].name)
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", eps[0].stillUrl)
        assertNull(eps[1].stillUrl)
        c.episodes("tmdb:9", "series", 1)
        assertEquals(1, http.requests.size)
        assertEquals(emptyList(), c.episodes("tmdb:9", "series", 2)) // a failure is an empty list
    }

    @Test
    fun trailersAreFilteredAndOrdered() = runTest {
        val videos = """{"results":[
          {"id":"1","key":"k1","name":"Teaser","site":"YouTube","type":"Teaser","official":true,"published_at":"2020-01-01"},
          {"id":"2","key":"k2","name":"Old trailer","site":"YouTube","type":"Trailer","official":false,"published_at":"2019-01-01"},
          {"id":"3","key":"k3","name":"Trailer","site":"YouTube","type":"Trailer","official":true,"published_at":"2020-06-01"},
          {"id":"4","key":"k4","name":"Vimeo","site":"Vimeo","type":"Trailer"},
          {"id":"5","key":"k5","name":"Bloopers","site":"YouTube","type":"Bloopers"},
          {"id":"6","key":"k6","name":"Newer unofficial trailer","site":"YouTube","type":"Trailer","official":false,"published_at":"2021-01-01"}]}"""
        val http = tmdbRouter("/movie/5/videos" to videos)
        val v = client(http).videos("tmdb:5", "movie")
        assertEquals(listOf("3", "6", "2", "1"), v.map { it.id })
        assertEquals("https://www.youtube.com/watch?v=k3", v[0].watchUrl)
        assertEquals("https://i.ytimg.com/vi/k3/hqdefault.jpg", v[0].thumbnailUrl)
        assertEquals("en,null", http.requests.single().param("include_video_language"))
    }

    @Test
    fun aShowsNetworkIsTheFirstOneWithAnIconAndMoviesHaveNone() = runTest {
        val http = tmdbRouter("/tv/9" to """{"networks":[{"name":"HBO"},{"name":"Netflix","logo_path":"/n.png"}]}""", "/tv/8" to """{"networks":[]}""")
        val c = client(http)
        val badge = c.network("tmdb:9", "series")!!
        assertEquals("Netflix", badge.name)
        assertEquals("https://image.tmdb.org/t/p/w92/n.png", badge.logo)
        c.network("tmdb:9", "series")
        assertNull(c.network("tmdb:8", "series"))
        assertNull(c.network("tmdb:8", "series"))
        assertNull(c.network("tmdb:9", "movie"))
        assertEquals(2, http.requests.size) // one per show, misses included
    }

    @Test
    fun traitsUseOriginCountryThenProductionCountries() = runTest {
        val http = tmdbRouter(
            "/tv/1" to """{"genres":[{"id":16,"name":"Animation"}],"original_language":"ja","origin_country":["JP"]}""",
            "/movie/2" to """{"genres":[{"id":18,"name":"Drama"}],"original_language":"fr","production_countries":[{"iso_3166_1":"FR"},{"name":"x"}]}""",
        )
        val c = client(http)
        val tv = c.traits("tmdb:1", "series")!!
        assertEquals(setOf(16), tv.genres)
        assertEquals(listOf("JP"), tv.countries)
        assertTrue(tv.isJapanese)
        assertEquals(listOf("FR"), c.traits("tmdb:2", "movie")!!.countries)
        assertNull(c.traits("tmdb:3", "movie"))
    }

    @Test
    fun recommendationsFallBackToSimilarAndOddPathsFailOnlyThatRequest() = runTest {
        val http = tmdbRouter("/movie/5/recommendations" to """{"results":[]}""", "/movie/5/similar" to ITEMS)
        assertEquals(2, client(http).recommendations("tmdb:5", "movie").size)
        assertEquals(listOf("/movie/5/recommendations", "/movie/5/similar"), http.requests.map { it.tmdbPath() })
        // An id from an add-on with odd characters can't reach the network as a different path.
        val odd = tmdbRouter()
        val c = client(odd)
        assertNull(c.tmdbIdentifier("tt1/../x?y=1", "movie")) // never a crash, at worst a miss
        assertTrue(odd.requests.size <= 1)
    }
}

package com.mediahub.pear.meta

import com.mediahub.pear.data.InMemorySecretStore
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.net.MemoryResponseCache
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import com.mediahub.pear.testing.urlPath
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REMOTE_SERIES = """{"data":[{"series":{"id":"81189"}}]}"""
private const val EPISODES = """{"data":{"episodes":[
  {"id":3,"number":2,"seasonNumber":1,"name":"B","image":"/banners/x.jpg","aired":"2020-01-08"},
  {"id":2,"number":1,"seasonNumber":1,"name":"A","image":"https://cdn/y.jpg"},
  {"id":9,"number":1,"seasonNumber":2,"name":"other season"}]}}"""

private fun tvdb(
    http: FakeHttp,
    secrets: InMemorySecretStore = InMemorySecretStore(),
    key: String = "KEY",
    pin: String = "",
    now: () -> Long = { 1_000_000L },
) = TvdbClient(http, apiKey = { key }, pin = { pin }, secrets = secrets, cache = MemoryResponseCache(), now = now)

/** Logs in with token T1 and answers the listed paths (prefix match), 404 for the rest. */
private fun router(vararg routes: Pair<String, String>) = FakeHttp { r ->
    val p = r.urlPath().removePrefix("/v4")
    when {
        p == "/login" -> reply("""{"data":{"token":"T1"}}""")
        else -> routes.firstOrNull { p.startsWith(it.first) }?.let { reply(it.second) } ?: reply("{}", 404)
    }
}

class TvdbClientTest {
    @Test
    fun logsInOnceThenReadsEpisodesInOrderForTheRightSeason() = runTest {
        val secrets = InMemorySecretStore()
        val http = router("/search/remoteid/tt1" to REMOTE_SERIES, "/series/81189/episodes/default" to EPISODES)
        val c = tvdb(http, secrets, pin = "1234")

        val eps = c.episodes("tt1", 1)
        assertEquals(listOf("A", "B"), eps.map { it.name })
        assertEquals("https://cdn/y.jpg", eps[0].imageUrl)
        assertEquals("https://artworks.thetvdb.com/banners/x.jpg", eps[1].imageUrl)
        assertEquals("2020-01-08", eps[1].aired)

        val login = http.requests.first { it.urlPath() == "/v4/login" }
        assertEquals("POST", login.method)
        assertEquals("application/json", login.headers["Content-Type"])
        assertTrue(login.body!!.contains("\"apikey\":\"KEY\"") && login.body!!.contains("\"pin\":\"1234\""), login.body)
        assertEquals("Bearer T1", http.requests.first { it.urlPath().contains("/search/") }.headers["Authorization"])
        val ep = http.requests.first { it.urlPath().contains("/episodes/") }
        assertEquals("1", Url(ep.url).parameters["season"])
        assertEquals("0", Url(ep.url).parameters["page"])
        assertEquals("T1", secrets.get(SecretKeys.TVDB_TOKEN))

        c.episodes("tt1", 1)
        assertEquals(1, http.requests.count { it.urlPath() == "/v4/login" })
    }

    @Test
    fun aSavedTokenIsReusedUntilItIsTwentyFiveDaysOld() = runTest {
        val secrets = InMemorySecretStore()
        var clock = 1_000_000L
        val http = router("/search/remoteid/" to REMOTE_SERIES, "/series/" to EPISODES)
        tvdb(http, secrets, now = { clock }).episodes("tt1", 1)
        assertEquals(1, http.requests.count { it.urlPath() == "/v4/login" })

        // A new run (new client, same secrets) within 25 days: no login.
        val http2 = router("/search/remoteid/" to REMOTE_SERIES, "/series/" to EPISODES)
        clock += 24L * 24 * 3600 * 1000
        tvdb(http2, secrets, now = { clock }).episodes("tt2", 1)
        assertEquals(0, http2.requests.count { it.urlPath() == "/v4/login" })

        // After 25 days: a fresh login.
        val http3 = router("/search/remoteid/" to REMOTE_SERIES, "/series/" to EPISODES)
        clock += 2L * 24 * 3600 * 1000
        tvdb(http3, secrets, now = { clock }).episodes("tt3", 1)
        assertEquals(1, http3.requests.count { it.urlPath() == "/v4/login" })
    }

    @Test
    fun aRejectedTokenIsReplacedOnceAndTheRequestRepeated() = runTest {
        var logins = 0
        val http = FakeHttp { r ->
            val p = r.urlPath().removePrefix("/v4")
            when {
                p == "/login" -> { logins++; reply("""{"data":{"token":"T$logins"}}""") }
                r.headers["Authorization"] == "Bearer T1" -> reply("{}", 401)
                p.startsWith("/search/remoteid/") -> reply(REMOTE_SERIES)
                p.startsWith("/series/") -> reply(EPISODES)
                else -> reply("{}", 404)
            }
        }
        val eps = tvdb(http).episodes("tt1", 1)
        assertEquals(2, eps.size)
        assertEquals(2, logins)
    }

    @Test
    fun withoutAKeyNothingIsAskedAndTheAnswersAreEmpty() = runTest {
        val http = router()
        val c = tvdb(http, key = "")
        assertEquals(false, c.hasKey)
        assertEquals(emptyList(), c.episodes("tt1", 1))
        assertNull(c.logo("tt1", "series"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aRejectedLoginFailsQuietly() = runTest {
        val http = FakeHttp { reply("{}", 401) }
        assertEquals(emptyList(), tvdb(http).episodes("tt1", 1))
        assertNull(tvdb(http).logo("tt1", "movie"))
    }

    @Test
    fun theLogoIsTheBestScoringEnglishOrLanguagelessClearLogo() = runTest {
        val series = """{"data":{"artworks":[
          {"image":"/a.png","type":23,"score":10,"language":"eng"},
          {"image":"/fr.png","type":23,"score":50,"language":"fra"},
          {"image":"/bg.png","type":22,"score":99},
          {"image":"/d.png","type":23,"score":20}]}}"""
        val http = router(
            "/search/remoteid/tt1" to REMOTE_SERIES,
            "/series/81189/artworks" to series,
            "/search/remoteid/tt2" to """{"data":[{"movie":{"id":5}}]}""",
            "/movies/5/extended" to """{"data":{"artworks":[{"image":"/m.png","type":25,"score":1},{"image":"/s.png","type":23,"score":9}]}}""",
        )
        val c = tvdb(http)
        assertEquals("https://artworks.thetvdb.com/d.png", c.logo("tt1", "series"))
        assertEquals("23", Url(http.requests.first { it.urlPath().contains("/artworks") }.url).parameters["type"])
        assertEquals("https://artworks.thetvdb.com/m.png", c.logo("tt2", "movie"))
        assertNull(c.logo("tt2", "series")) // that IMDb id is a movie on TheTVDB
    }
}

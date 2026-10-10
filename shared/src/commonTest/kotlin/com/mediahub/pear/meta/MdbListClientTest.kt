package com.mediahub.pear.meta

import com.mediahub.pear.net.MemoryResponseCache
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.param
import com.mediahub.pear.testing.reply
import com.mediahub.pear.testing.urlPath
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun mdb(http: FakeHttp, key: String = "K", now: () -> Long = { 0L }) =
    MdbListClient(http, apiKey = { key }, cache = MemoryResponseCache(), now = now)

private fun route(vararg routes: Pair<String, String>) = FakeHttp { r ->
    routes.firstOrNull { it.first == r.urlPath() }?.let { reply(it.second) } ?: reply("{}", 404)
}

class MdbListClientTest {
    @Test
    fun ratingsAreLabelledFormattedAndOrdered() = runTest {
        val body = """{"ratings":[
          {"source":"trakt","value":77.6},{"source":"imdb","value":7.84},{"source":"tomatoes","value":91.0},
          {"source":"popcorn","value":88},{"source":"metacritic","value":null},{"source":"letterboxd","value":3.96},
          {"source":"imdb","value":1.0}]}"""
        val http = route("/imdb/movie/tt1" to body)
        val r = mdb(http).ratings("tt1", "movie")
        assertEquals(
            listOf("IMDb" to "7.8", "Rotten Tomatoes" to "91%", "RT Audience" to "88%", "Letterboxd" to "4.0", "Trakt" to "77%"),
            r.map { it.label to it.text },
        )
        assertEquals(7.84, r[0].score) // the first value of a source wins
        assertEquals("K", http.requests.single().param("apikey"))
    }

    @Test
    fun showsUseTheShowEndpointAndOtherIdsNeverReachTheNetwork() = runTest {
        val http = route("/imdb/show/tt2" to """{"ratings":[]}""")
        val c = mdb(http)
        assertEquals(emptyList(), c.ratings("tt2", "series"))
        assertEquals(emptyList(), c.ratings("kitsu:1", "series"))
        assertEquals(1, http.requests.size)
        c.ratings("tt2", "series") // an empty list from a good answer is remembered
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aFailureIsEmptyAndNotRemembered() = runTest {
        var ok = false
        val http = FakeHttp { if (ok) reply("""{"ratings":[{"source":"imdb","value":5}]}""") else reply("{}", 500) }
        val c = mdb(http)
        assertEquals(emptyList(), c.ratings("tt1", "movie"))
        ok = true
        assertEquals("5.0", c.ratings("tt1", "movie").single().text)
    }

    @Test
    fun oneDecimalRoundsLikePrintf() {
        assertEquals("7.8", MdbListClient.oneDecimal(7.84))
        assertEquals("10.0", MdbListClient.oneDecimal(10.0))
        assertEquals("0.0", MdbListClient.oneDecimal(0.0))
        assertEquals("8.1", MdbListClient.oneDecimal(8.06))
    }

    @Test
    fun listItemsComeInBothShapesAndNeedAnImdbId() = runTest {
        val object1 = """{"movies":[{"title":"A","imdb_id":"tt1","release_year":2001}],"shows":[{"title":"B","imdb_id":"tt2"},{"title":"No id"}]}"""
        val http = route("/lists/7/items" to object1, "/lists/8/items" to """[{"title":"C","imdb_id":"tt3","mediatype":"show"},{"title":"D","imdb_id":"tt4"}]""")
        val c = mdb(http)
        val a = c.items(7)
        assertEquals(listOf("tt1" to "movie", "tt2" to "series"), a.map { it.id to it.type })
        assertEquals("2001", a[0].releaseInfo)
        assertEquals("A", a[0].name)
        assertEquals("30", http.requests.single().param("limit"))
        assertEquals(listOf("tt3" to "series", "tt4" to "movie"), c.items(8).map { it.id to it.type })
    }

    @Test
    fun anUnreadableHalfIsJustEmptyInTheObjectShape() = runTest {
        val http = route("/lists/1/items" to """{"movies":[{"nope":1}],"shows":[{"title":"S","imdb_id":"tt9"}]}""")
        assertEquals(listOf("tt9"), mdb(http).items(1).map { it.id })
    }

    @Test
    fun listItemsAreKeptForThirtyMinutes() = runTest {
        var clock = 0L
        val http = route("/lists/1/items" to """{"movies":[{"title":"A","imdb_id":"tt1"}],"shows":[]}""")
        val c = mdb(http, now = { clock })
        c.items(1)
        clock += 29 * 60_000
        c.items(1)
        assertEquals(1, http.requests.size)
        clock += 2 * 60_000
        c.items(1)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun userListsAreNeverKeptOnDiskAndWithoutAKeyNothingIsAsked() = runTest {
        val http = route("/lists/user" to """[{"id":5,"name":"Mine","items":10},{"id":6,"name":"Other"}]""")
        val c = mdb(http)
        val lists = c.userLists()
        assertEquals(listOf(5 to "Mine", 6 to "Other"), lists.map { it.id to it.name })
        assertEquals(null, lists[1].items)
        c.userLists()
        assertEquals(2, http.requests.size) // asked again: it belongs to the account

        val none = route()
        val d = mdb(none, key = "")
        assertEquals(false, d.hasKey)
        assertEquals(emptyList(), d.userLists())
        assertEquals(emptyList(), d.items(1))
        assertTrue(none.requests.isEmpty())
    }
}

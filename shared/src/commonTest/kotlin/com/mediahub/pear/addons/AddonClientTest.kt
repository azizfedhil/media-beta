package com.mediahub.pear.addons

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.net.BadStatusException
import com.mediahub.pear.net.CachedBody
import com.mediahub.pear.net.MemoryResponseCache
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val BASE = "https://addon.example/"
internal const val MANIFEST_URL = BASE + "manifest.json"

internal fun manifestJson(
    id: String = "org.test",
    resources: String = """["catalog","stream"]""",
    catalogs: String = """[{"type":"movie","id":"top","name":"Top"}]""",
    version: String = "1.0.0",
) = """{"id":"$id","name":"Test $id","version":"$version","resources":$resources,"types":["movie","series"],"catalogs":$catalogs}"""

internal fun addonOf(url: String = MANIFEST_URL, json: String = manifestJson()) = Addon(url, AddonManifest.parse(json))

internal val TOP = AddonManifest.CatalogDef("movie", "top", "Top")

internal const val TWO_METAS = """{"metas":[{"id":"tt1","type":"movie","name":"A"},{"id":"tt2","type":"movie","name":"B"},{"bad":1}]}"""

/** Answers each exact address with its body, 404 for anything else. */
internal fun routes(vararg pairs: Pair<String, String>) =
    FakeHttp { r -> pairs.firstOrNull { it.first == r.url }?.let { reply(it.second) } ?: reply("{}", 404) }

private class KeyLog : ResponseCache {
    val keys = ArrayList<String>()
    override suspend fun get(key: String): CachedBody? {
        keys.add(key)
        return null
    }
    override suspend fun put(key: String, body: ByteArray, nowMillis: Long) {
        keys.add(key)
    }
}

private const val HOUR = 60 * 60 * 1000L

class AddonClientManifestTest {
    @Test
    fun aManifestIsFetchedAndParsed() = runTest {
        val http = routes(MANIFEST_URL to manifestJson())
        val m = AddonClient(http).manifest(MANIFEST_URL)
        assertEquals("org.test", m.id)
        assertEquals(MANIFEST_URL, http.requests.single().url)
    }

    @Test
    fun aFailedFetchIsRetriedTwice() = runTest {
        var calls = 0
        val http = FakeHttp {
            calls++
            if (calls < 3) reply("", 503) else reply(manifestJson())
        }
        val m = AddonClient(http, retryDelayMillis = { 0L }).manifest(MANIFEST_URL)
        assertEquals("org.test", m.id)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun theWaitBetweenTriesGrows() = runTest {
        val waits = ArrayList<Int>()
        val http = FakeHttp { reply("", 500) }
        val c = AddonClient(http, retryDelayMillis = { n -> waits.add(n); 0L })
        assertFailsWith<BadStatusException> { c.manifest(MANIFEST_URL) }
        assertEquals(listOf(1, 2), waits)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun aManifestThatIsNotAManifestFailsAfterTheRetries() = runTest {
        val http = FakeHttp { reply("[]") }
        assertFailsWith<SerializationException> { AddonClient(http, retryDelayMillis = { 0L }).manifest(MANIFEST_URL) }
        assertEquals(3, http.requests.size)
    }

    @Test
    fun aManifestIsKeptSixHoursAndFreshAsksAgain() = runTest {
        var t = 0L
        val http = routes(MANIFEST_URL to manifestJson())
        val c = AddonClient(http, MemoryResponseCache(), now = { t })
        c.manifest(MANIFEST_URL)
        c.manifest(MANIFEST_URL)
        assertEquals(1, http.requests.size)
        c.manifest(MANIFEST_URL, fresh = true) // reload: ignores what is kept
        assertEquals(2, http.requests.size)
        t = 7 * HOUR
        c.manifest(MANIFEST_URL)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun theAddonAddressNeverReachesTheCacheKey() = runTest {
        val secret = "https://addon.example/SECRET123/manifest.json"
        val log = KeyLog()
        val http = routes(secret to manifestJson(), "https://addon.example/SECRET123/catalog/movie/top.json" to TWO_METAS)
        val c = AddonClient(http, log)
        c.manifest(secret)
        c.catalog(addonOf(secret), TOP)
        assertTrue(log.keys.isNotEmpty())
        assertTrue(log.keys.all { it.startsWith("addon:") })
        assertTrue(log.keys.none { "SECRET123" in it || "addon.example" in it })
    }
}

class AddonClientCatalogTest {
    @Test
    fun aCatalogueIsFetchedAndBadItemsAreSkipped() = runTest {
        val http = routes(BASE + "catalog/movie/top.json" to TWO_METAS)
        val items = AddonClient(http).catalog(addonOf(), TOP)
        assertEquals(listOf("tt1", "tt2"), items.map { it.id })
    }

    @Test
    fun searchAndSkipBecomeExtras() = runTest {
        val url = BASE + "catalog/movie/top/search=a%20b%26c&skip=20.json"
        val http = routes(url to TWO_METAS)
        val c = AddonClient(http)
        assertEquals(2, c.catalog(addonOf(), TOP, skip = 20, search = " a b&c ").size)
        assertEquals(url, http.requests.single().url)
    }

    @Test
    fun skipZeroAndBlankSearchAddNothing() = runTest {
        val http = routes(BASE + "catalog/movie/top.json" to TWO_METAS)
        AddonClient(http).catalog(addonOf(), TOP, skip = 0, search = "   ")
        assertEquals(BASE + "catalog/movie/top.json", http.requests.single().url)
    }

    @Test
    fun onlySkipAddsAPathPart() = runTest {
        val http = routes(BASE + "catalog/movie/top/skip=100.json" to TWO_METAS)
        AddonClient(http).catalog(addonOf(), TOP, skip = 100)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun nonAsciiSearchIsEncodedAsUtf8() = runTest {
        val http = routes(BASE + "catalog/movie/top/search=caf%C3%A9.json" to TWO_METAS)
        AddonClient(http).catalog(addonOf(), TOP, search = "café")
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aCatalogueIsKeptHalfAnHourPerPage() = runTest {
        var t = 0L
        val http = FakeHttp { reply(TWO_METAS) }
        val c = AddonClient(http, MemoryResponseCache(), now = { t })
        c.catalog(addonOf(), TOP)
        c.catalog(addonOf(), TOP)
        assertEquals(1, http.requests.size)
        c.catalog(addonOf(), TOP, skip = 20) // another page is another answer
        assertEquals(2, http.requests.size)
        t = 31 * 60 * 1000L
        c.catalog(addonOf(), TOP)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun anOlderCopyCoversAFailingAddon() = runTest {
        var t = 0L
        var down = false
        val http = FakeHttp { if (down) reply("", 502) else reply(TWO_METAS) }
        val c = AddonClient(http, MemoryResponseCache(), now = { t })
        c.catalog(addonOf(), TOP)
        t = 2 * HOUR
        down = true
        assertEquals(2, c.catalog(addonOf(), TOP).size)
    }

    @Test
    fun aBrokenAnswerIsAnErrorAndIsNotKept() = runTest {
        val http = FakeHttp { reply("""{"nometas":1}""") }
        val c = AddonClient(http, MemoryResponseCache())
        assertFailsWith<SerializationException> { c.catalog(addonOf(), TOP) }
        assertFailsWith<SerializationException> { c.catalog(addonOf(), TOP) }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun aBadStatusIsAnError() = runTest {
        val http = routes()
        assertFailsWith<BadStatusException> { AddonClient(http).catalog(addonOf(), TOP) }
    }

    @Test
    fun clearingHidesEarlierAnswersForTheRun() = runTest {
        var t = 0L
        val http = FakeHttp { reply(TWO_METAS) }
        val c = AddonClient(http, MemoryResponseCache(), now = { t })
        c.catalog(addonOf(), TOP)
        c.catalog(addonOf(), TOP)
        assertEquals(1, http.requests.size)
        t = 1_000
        c.clearCache()
        t = 2_000
        c.catalog(addonOf(), TOP)
        assertEquals(2, http.requests.size)
        c.catalog(addonOf(), TOP) // the new answer is kept
        assertEquals(2, http.requests.size)
    }

    @Test
    fun clearingLeavesOtherAnswersAlone() = runTest {
        var t = 0L
        val shared = MemoryResponseCache()
        shared.put("tmdb:/trending", "x".encodeToByteArray(), 0)
        val c = AddonClient(FakeHttp { reply(TWO_METAS) }, shared, now = { t })
        t = 5
        c.clearCache()
        assertEquals("x", shared.get("tmdb:/trending")?.body?.decodeToString())
    }
}

class AddonClientStreamTest {
    private fun stream(url: String = "https://cdn.example/a.mp4") = """{"streams":[{"name":"S","url":"$url"}]}"""

    private val a = addonOf("https://a.example/manifest.json", manifestJson("a"))
    private val b = addonOf("https://b.example/manifest.json", manifestJson("b", resources = """["catalog"]"""))
    private val c = addonOf("https://c.example/manifest.json", manifestJson("c"))
    private val d = addonOf("https://d.example/manifest.json", manifestJson("d"))

    @Test
    fun onlyAddonsThatDeclareStreamsAreAsked() = runTest {
        val http = routes(
            "https://a.example/stream/movie/tt1.json" to stream(),
            "https://c.example/stream/movie/tt1.json" to stream(),
        )
        val got = AddonClient(http).streams("tt1", "movie", listOf(a, b, c))
        assertEquals(setOf("a", "c"), got.map { it.first.manifest.id }.toSet())
        assertEquals(2, http.requests.size) // b has no stream resource
    }

    @Test
    fun aFailingOrSlowAddonIsSkippedWithoutHoldingUpTheRest() = runTest {
        val http = FakeHttp { r ->
            when {
                r.url.startsWith("https://a.example/") -> reply(stream())
                r.url.startsWith("https://c.example/") -> reply("", 500)
                r.url.startsWith("https://d.example/") -> {
                    delay(20_000) // longer than the 8 s deadline
                    reply(stream())
                }
                else -> reply("{}", 404)
            }
        }
        val got = AddonClient(http).streams("tt1", "movie", listOf(a, c, d))
        assertEquals(listOf("a"), got.map { it.first.manifest.id })
    }

    @Test
    fun theDeadlineIsPerAddon() = runTest {
        val http = FakeHttp { delay(7_000); reply(stream()) } // just inside the deadline
        val got = AddonClient(http).streams("tt1", "movie", listOf(a, c))
        assertEquals(2, got.size)
    }

    @Test
    fun answersArriveInTheOrderTheyAreReadyAndCanBePutBackInUserOrder() = runTest {
        val http = FakeHttp { r ->
            if (r.url.startsWith("https://a.example/")) delay(500)
            reply(stream())
        }
        val arrived = ArrayList<String>()
        AddonClient(http).streamUpdates("tt1", "movie", listOf(a, c)).collect { arrived.add(it.first.manifest.id) }
        assertEquals(listOf("c", "a"), arrived)
        val ordered = AddonClient.ordered(listOf(c to emptyList<StreamItem>(), a to emptyList<StreamItem>()), listOf(a, b, c))
        assertEquals(listOf("a", "c"), ordered.map { it.first.manifest.id })
    }

    @Test
    fun anEmptyListIsStillAnAnswer() = runTest {
        val http = routes("https://a.example/stream/movie/tt1.json" to """{"streams":[]}""")
        val got = AddonClient(http).streams("tt1", "movie", listOf(a))
        assertEquals(1, got.size)
        assertTrue(got.single().second.isEmpty())
    }

    @Test
    fun aMalformedAnswerIsSkipped() = runTest {
        val http = routes("https://a.example/stream/movie/tt1.json" to """{"nostreams":1}""")
        assertTrue(AddonClient(http).streams("tt1", "movie", listOf(a)).isEmpty())
    }

    @Test
    fun episodeIdsKeepTheirColonsAndOtherCharactersAreEscaped() = runTest {
        val http = FakeHttp { reply(stream()) }
        val client = AddonClient(http)
        client.streams("tt1:2:3", "series", listOf(a))
        client.streams("a b/c", "movie", listOf(a))
        assertEquals("https://a.example/stream/series/tt1:2:3.json", http.requests[0].url)
        assertEquals("https://a.example/stream/movie/a%20b%2Fc.json", http.requests[1].url)
    }

    @Test
    fun streamsAreNeverCached() = runTest {
        val http = FakeHttp { reply(stream()) }
        val client = AddonClient(http, MemoryResponseCache())
        client.streams("tt1", "movie", listOf(a))
        client.streams("tt1", "movie", listOf(a))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun theFilterRunsOnEachAddonsList() = runTest {
        val http = FakeHttp { reply("""{"streams":[{"url":"https://x/a.mp4"},{"infoHash":"0123456789abcdef0123456789abcdef01234567"}]}""") }
        val client = AddonClient(http, streamFilter = { items: List<StreamItem> -> items.filter { it.url != null } })
        val got = client.streams("tt1", "movie", listOf(a))
        assertEquals(1, got.single().second.size)
    }
}

class AddonClientMetaTest {
    private val aio = addonOf(
        "https://m.example/manifest.json",
        manifestJson("m", resources = """[{"name":"meta","types":["movie"],"idPrefixes":["tt"]}]"""),
    )
    private val url = "https://m.example/meta/movie/tt1.json"
    private val body = """{"meta":{"genres":["Action","Sci-Fi"],"logo":"https://x.example/l.png"}}"""

    @Test
    fun genresAndLogoAreReadAndKeptForTheRun() = runTest {
        val http = routes(url to body)
        val client = AddonClient(http)
        val x = client.metaExtras(aio, "movie", "tt1")
        assertEquals(listOf("Action", "Sci-Fi"), x.genres)
        assertEquals("https://x.example/l.png", x.logo)
        client.metaExtras(aio, "movie", "tt1")
        assertEquals(1, http.requests.size)
    }

    @Test
    fun anIdTheAddonDoesNotServeCostsNoRequest() = runTest {
        val http = routes()
        val client = AddonClient(http)
        assertEquals(MetaExtras.Empty, client.metaExtras(aio, "movie", "kitsu:1"))
        assertEquals(MetaExtras.Empty, client.metaExtras(aio, "series", "tt1"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aFailureIsNotRememberedButAnUnreadableAnswerIs() = runTest {
        var calls = 0
        val http = FakeHttp {
            calls++
            when (calls) {
                1 -> reply("", 500)
                2 -> reply(body)
                else -> reply("nonsense")
            }
        }
        val client = AddonClient(http)
        assertEquals(MetaExtras.Empty, client.metaExtras(aio, "movie", "tt1")) // failed: asked again next time
        assertEquals(listOf("Action", "Sci-Fi"), client.metaExtras(aio, "movie", "tt1").genres)
        assertEquals(2, http.requests.size)

        val client2 = AddonClient(FakeHttp { reply("""{"meta":5}""") })
        assertEquals(MetaExtras.Empty, client2.metaExtras(aio, "movie", "tt1"))
    }

    @Test
    fun oneOddFieldDoesNotLoseTheOther() = runTest {
        val http = routes(url to """{"meta":{"genres":"Action","logo":"https://x.example/l.png"}}""")
        val x = AddonClient(http).metaExtras(aio, "movie", "tt1")
        assertTrue(x.genres.isEmpty())
        assertEquals("https://x.example/l.png", x.logo)
        assertNull(MetaExtras.Empty.logo)
        assertFalse(MetaExtras.Empty.genres.isNotEmpty())
    }
}

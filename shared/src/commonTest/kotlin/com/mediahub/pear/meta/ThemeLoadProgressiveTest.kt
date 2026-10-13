package com.mediahub.pear.meta

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.net.FactStore
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import com.mediahub.pear.testing.tmdbRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One TMDB page of titles with posters. The client interleaves the shows and movies pages itself. */
private fun page(vararg ids: Int): String = "{\"results\":[" + ids.joinToString(",") { id ->
    "{\"id\":$id,\"title\":\"T$id\",\"poster_path\":\"/p$id.jpg\",\"genre_ids\":[18],\"original_language\":\"en\"}"
} + "]}"

/** Every keyword resolves to the same title, so each candidate row has six titles to look up on the add-on. */
private fun tmdbFake(): FakeHttp = tmdbRouter(
    "/search/keyword" to """{"results":[{"id":10,"name":"keyword"}]}""",
    "/discover/tv" to page(1, 2, 3),
    "/discover/movie" to page(4, 5, 6),
    "/genre/tv/list" to """{"genres":[{"id":18,"name":"Drama"}]}""",
    "/genre/movie/list" to """{"genres":[{"id":18,"name":"Drama"}]}""",
)

private fun addon(): Addon = Addon(
    manifestUrl = "https://addon.example/manifest.json",
    manifest = AddonManifest(
        id = "org.example.meta",
        name = "Meta",
        resources = listOf(AddonManifest.Resource(name = "meta")),
    ),
)

private fun metaReply(genres: List<String>, logo: String? = null): String {
    val list = genres.joinToString(",") { "\"$it\"" }
    val logoField = if (logo == null) "" else ",\"logo\":\"$logo\""
    return "{\"meta\":{\"genres\":[$list]$logoField}}"
}

/** Loads Home's theme rows the way Home does: rows first, enrichment launched into the same scope. */
private suspend fun TestScope.loadHome(
    addonHttp: FakeHttp,
    published: MutableList<List<ThemeRow>>,
    withAddon: Boolean = true,
    lowPower: Boolean = false,
    logoHttp: FakeHttp = FakeHttp { reply("", 404) },
) {
    ThemeCatalog.loadProgressive(
        tmdb = TmdbClient(
            http = tmdbFake(),
            apiKey = { "" },
            cache = ResponseCache.None,
            facts = FactStore.None,
            now = { 0L },
            today = { "2026-10-10" },
            persistDelayMillis = 600_000,
        ),
        count = 3,
        dayNumber = 0,
        rules = ContentRules(),
        http = logoHttp,
        enrichWith = if (withAddon) addon() else null,
        addonClient = AddonClient(addonHttp, retryDelayMillis = { 0L }),
        enrichScope = this,
        lowPower = { lowPower },
        update = { published.add(it) },
    )
}

class ThemeLoadProgressiveTest {

    @Test
    fun theRowsAreReadyBeforeTheAddOnAnswers() = runTest {
        val answer = CompletableDeferred<Unit>()
        val addonHttp = FakeHttp {
            answer.await()
            reply(metaReply(listOf("Crime", "Drama")))
        }
        val published = ArrayList<List<ThemeRow>>()
        loadHome(addonHttp, published)

        // loadProgressive has returned, and the add-on is still on its first request.
        assertEquals(3, published.last().size)
        assertEquals(listOf("Drama"), published.last()[0].items[0].genres)

        answer.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Crime", "Drama"), published.last()[0].items[0].genres)
    }

    @Test
    fun lowPowerLeavesTheAddOnAlone() = runTest {
        val addonHttp = FakeHttp { reply(metaReply(listOf("Crime"))) }
        val published = ArrayList<List<ThemeRow>>()
        loadHome(addonHttp, published, lowPower = true)
        advanceUntilIdle()
        assertTrue(addonHttp.requests.isEmpty())
        assertEquals(3, published.last().size)
        assertEquals(listOf("Drama"), published.last()[0].items[0].genres)
    }

    @Test
    fun withoutAnAddOnTheAddOnIsNeverAsked() = runTest {
        val addonHttp = FakeHttp { reply(metaReply(listOf("Crime"))) }
        val published = ArrayList<List<ThemeRow>>()
        loadHome(addonHttp, published, withAddon = false)
        advanceUntilIdle()
        assertTrue(addonHttp.requests.isEmpty())
        assertEquals(3, published.last().size)
    }

    @Test
    fun withAnAddOnEachShownTitleIsLookedUpAndItsGenresTakeOver() = runTest {
        // The control for the two tests above: the same setup with power on must reach the add-on.
        val addonHttp = FakeHttp { reply(metaReply(listOf("Crime", "Drama"))) }
        val published = ArrayList<List<ThemeRow>>()
        loadHome(addonHttp, published)
        advanceUntilIdle()
        assertTrue(addonHttp.requests.isNotEmpty())
        assertTrue(addonHttp.requests.all { it.url.contains("/meta/") })
        assertEquals(listOf("Crime", "Drama"), published.last()[0].items[0].genres)
    }

    @Test
    fun aLogoThatDoesNotLoadIsNotShown() = runTest {
        val addonHttp = FakeHttp { reply(metaReply(listOf("Crime"), logo = "https://img.example/logo.png")) }
        val logoHttp = FakeHttp { reply("", 404) }
        val published = ArrayList<List<ThemeRow>>()
        loadHome(addonHttp, published, logoHttp = logoHttp)
        advanceUntilIdle()
        val first = published.last()[0].items[0]
        assertEquals(listOf("Crime"), first.genres)
        assertNull(first.item.logo)
        assertTrue(logoHttp.requests.isNotEmpty())
        assertTrue(logoHttp.requests.all { it.url == "https://img.example/logo.png" })
    }
}

package com.mediahub.pear.addons

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OPEN = "https://opensubtitles-v3.strem.io/subtitles/"

private val openSubtitles = """{"subtitles":[
    {"id":"1","url":"https://dl.example/os-en.srt","lang":"eng"},
    {"id":"2","url":"https://dl.example/os-fr.srt","lang":"fra"}]}"""

private val addonSubtitles = """{"subtitles":[
    {"id":"a1","url":"https://dl.example/ad-en.srt","lang":"eng"},
    {"id":"a2","url":"https://dl.example/os-en.srt","lang":"eng"}]}"""

private fun addon(id: String, name: String, subtitles: Boolean = true) = Addon(
    "https://$id.example/manifest.json",
    AddonManifest(
        id = id,
        name = name,
        resources = if (subtitles) listOf(AddonManifest.Resource("subtitles", types = listOf("movie", "series"))) else null,
    ),
)

class SubtitleSourcesTest {
    private fun sources(http: FakeHttp, enabled: Boolean = true, addons: List<Addon>) =
        SubtitleSources(http, OpenSubtitlesClient(http, enabled = { enabled }), { addons })

    /** OpenSubtitles answers, the add-on "sub" answers with a subtitles resource, the add-on "nope" has none. */
    private fun router(addonStatus: Int = 200) = FakeHttp { req ->
        when {
            req.url.startsWith(OPEN) -> reply(openSubtitles)
            req.url.startsWith("https://sub.example/") -> if (addonStatus == 200) reply(addonSubtitles) else reply("", addonStatus)
            else -> reply("", 404)
        }
    }

    @Test
    fun openSubtitlesComesFirstThenTheAddonNamedAsItsSourceWithDuplicatesDropped() = runTest {
        val http = router()
        val list = sources(http, addons = listOf(addon("sub", "Sub Source"))).search("tt1", null, null)
        assertEquals(
            listOf("https://dl.example/os-en.srt", "https://dl.example/os-fr.srt", "https://dl.example/ad-en.srt"),
            list.map { it.url },
        )
        val fromAddon = list.single { it.url.endsWith("ad-en.srt") }
        assertEquals("Sub Source", fromAddon.source)
        assertTrue(fromAddon.id.endsWith("|a1"), fromAddon.id)
        assertEquals("OpenSubtitles", list.first().source)
    }

    @Test
    fun aSeriesEpisodeIsAskedForByItsEpisodeId() = runTest {
        val http = router()
        sources(http, addons = listOf(addon("sub", "Sub Source"))).search("tt1", 1, 2)
        val paths = http.requests.map { it.url }
        assertTrue(paths.any { it == OPEN + "series/tt1:1:2.json" }, paths.toString())
        assertTrue(paths.any { it.startsWith("https://sub.example/subtitles/series/tt1:1:2.json") }, paths.toString())
    }

    @Test
    fun anAddonWithoutASubtitlesResourceIsNotAsked() = runTest {
        val http = router()
        sources(http, addons = listOf(addon("nope", "No Subs", subtitles = false))).search("tt1", null, null)
        assertTrue(http.requests.none { it.url.contains("nope.example") }, http.requests.map { it.url }.toString())
    }

    @Test
    fun aFailingAddonLeavesOpenSubtitlesAlone() = runTest {
        val http = router(addonStatus = 500)
        val list = sources(http, addons = listOf(addon("sub", "Sub Source"))).search("tt1", null, null)
        assertEquals(listOf("https://dl.example/os-en.srt", "https://dl.example/os-fr.srt"), list.map { it.url })
    }

    @Test
    fun aTitleWithoutAnImdbIdIsNotLookedUp() = runTest {
        val http = router()
        assertEquals(emptyList<OnlineSubtitle>(), sources(http, addons = listOf(addon("sub", "Sub Source"))).search("12345", null, null))
        assertEquals(0, http.requests.size)
    }

    @Test
    fun theSwitchTurnsAllOfItOff() = runTest {
        val http = router()
        assertEquals(emptyList<OnlineSubtitle>(), sources(http, enabled = false, addons = listOf(addon("sub", "Sub Source"))).search("tt1", null, null))
        assertEquals(0, http.requests.size)
    }

    @Test
    fun anAnswerIsKeptForTheRun() = runTest {
        val http = router()
        val source = sources(http, addons = listOf(addon("sub", "Sub Source")))
        source.search("tt1", 1, 2)
        val asked = http.requests.size
        source.search("tt1", 1, 2)
        assertEquals(asked, http.requests.size)
    }
}

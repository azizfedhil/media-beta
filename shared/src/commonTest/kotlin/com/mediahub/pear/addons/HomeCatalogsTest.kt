package com.mediahub.pear.addons

import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun addonWith(count: Int, id: String = "org.test", name: String? = null, url: String = "https://$id.example/manifest.json"): Addon {
    val cats = (1..count).joinToString(",", "[", "]") { """{"type":"movie","id":"c$it","name":"C$it"}""" }
    val json = manifestJson(id, catalogs = cats).let { if (name == null) it else it.replace("\"name\":\"Test $id\"", "\"name\":\"$name\"") }
    return Addon(url, AddonManifest.parse(json))
}

class HomeJobsTest {
    @Test
    fun everyBrowsableCatalogueGetsARowInAddonOrder() {
        val a = addonWith(2, "a")
        val b = addonWith(1, "b")
        val jobs = HomeCatalogs.jobs(listOf(a, b), ContentRules(), emptySet())
        assertEquals(listOf("a/c1", "a/c2", "b/c1"), jobs.map { (ad, c) -> ad.manifest.id + "/" + c.id })
    }

    @Test
    fun searchOnlyCataloguesAreNotRows() {
        val a = Addon(
            "https://a.example/manifest.json",
            AddonManifest.parse(manifestJson("a", catalogs = """[{"type":"movie","id":"s","extra":[{"name":"search","isRequired":true}]},{"type":"movie","id":"ok"}]""")),
        )
        assertEquals(listOf("ok"), HomeCatalogs.jobs(listOf(a), ContentRules(), emptySet()).map { it.second.id })
    }

    @Test
    fun hiddenRowsDoNotUseUpTheTwelveSlots() {
        val a = addonWith(15, "a")
        val hidden = (1..3).map { "addon:a/movie/c$it" }.toSet()
        val jobs = HomeCatalogs.jobs(listOf(a), ContentRules(hiddenRows = hidden), emptySet())
        assertEquals(12, jobs.size)
        assertEquals("c4", jobs.first().second.id) // the three hidden ones were dropped before the cap
    }

    @Test
    fun withoutAPickerTheCapIsTwelve() {
        assertEquals(12, HomeCatalogs.jobs(listOf(addonWith(15, "a")), ContentRules(), emptySet()).size)
        assertEquals(12, HomeCatalogs.jobs(listOf(addonWith(8, "a"), addonWith(8, "b")), ContentRules(), emptySet()).size)
    }

    @Test
    fun pickerAddonsShowOnlyWhatWasAddedAndAreNeverCapped() {
        val aio = addonWith(15, "aiometadata")
        val added = (1..14).map { "addon:aiometadata/movie/c$it" }.toSet()
        val rules = ContentRules(addedRows = added)
        assertEquals(14, HomeCatalogs.jobs(listOf(aio), rules, emptySet()).size)
        assertTrue(HomeCatalogs.jobs(listOf(aio), ContentRules(), emptySet()).isEmpty()) // nothing until added
    }

    @Test
    fun aPickerSwitchedOnByHandBehavesTheSame() {
        val a = addonWith(3, "a")
        val rules = ContentRules(addedRows = setOf("addon:a/movie/c2"))
        assertEquals(3, HomeCatalogs.jobs(listOf(a), rules, emptySet()).size)
        assertEquals(listOf("c2"), HomeCatalogs.jobs(listOf(a), rules, setOf(a.id)).map { it.second.id })
    }

    @Test
    fun pickerRowsDoNotUseUpTheSlotsOfOtherAddons() {
        val aio = addonWith(3, "aiometadata")
        val a = addonWith(15, "a")
        val rules = ContentRules(addedRows = setOf("addon:aiometadata/movie/c1", "addon:aiometadata/movie/c2"))
        assertEquals(2 + 12, HomeCatalogs.jobs(listOf(aio, a), rules, emptySet()).size)
    }

    @Test
    fun showsOnHomeUsesTheManifestIdNotTheAddress() {
        val a = addonWith(1, "org.x", url = "https://host.example/SECRET/manifest.json")
        val cat = a.homeCatalogs.single()
        assertFalse(ContentRules(hiddenRows = setOf("addon:org.x/movie/c1")).showsOnHome(a, cat, picker = false))
        assertTrue(ContentRules(addedRows = setOf("addon:org.x/movie/c1")).showsOnHome(a, cat, picker = true))
        assertEquals("addon:org.x/movie/c1", a.rowKey(cat))
    }

    @Test
    fun rowTitlesNameTheKindOfTitle() {
        assertEquals("Top Movies", HomeCatalogs.rowTitle(AddonManifest.CatalogDef("movie", "top", "Top")))
        assertEquals("Top Series", HomeCatalogs.rowTitle(AddonManifest.CatalogDef("series", "top", "Top")))
        assertEquals("Top Anime", HomeCatalogs.rowTitle(AddonManifest.CatalogDef("anime", "top", "Top")))
        assertEquals("top Movies", HomeCatalogs.rowTitle(AddonManifest.CatalogDef("movie", "top")))
    }
}

class HomeRowsLoadTest {
    private val addon = addonWith(4, "org.test", url = "https://h.example/manifest.json")

    private fun http() = FakeHttp { r ->
        when (r.url) {
            "https://h.example/catalog/movie/c1.json" -> reply("""{"metas":[{"id":"tt1","type":"movie","name":"A"},{"id":"tt2","type":"movie","name":"B"}]}""")
            "https://h.example/catalog/movie/c2.json" -> reply("""{"metas":[]}""")
            "https://h.example/catalog/movie/c3.json" -> reply("", 500)
            "https://h.example/catalog/movie/c4.json" -> reply("""{"metas":[{"id":"xa","type":"movie","name":"X"},{"id":"xb","type":"movie","name":"Y"}]}""")
            else -> reply("{}", 404)
        }
    }

    @Test
    fun emptyFailedAndFullyFilteredCataloguesGetNoRow() = runTest {
        val jobs = HomeCatalogs.jobs(listOf(addon), ContentRules(), emptySet())
        val res = HomeCatalogs.loadRows(AddonClient(http()), jobs, filter = { items -> items.filter { !it.id.startsWith("x") } })
        assertEquals(4, res.jobs)
        assertEquals(1, res.failures) // only c3 could not be fetched; empty and filtered ones are not failures
        assertFalse(res.allFailed)
        val row = res.rows.single()
        assertEquals("https://h.example/manifest.json/movie/c1", row.id)
        assertEquals("C1 Movies", row.title)
        assertEquals(listOf("tt1", "tt2"), row.items.map { it.id })
        assertEquals(2, row.rawCount)
        assertEquals("addon:org.test/movie/c1", row.prefKey)
        assertEquals("film.stack", row.symbol)
        assertEquals(CatalogSource.FromAddon(addon, addon.homeCatalogs[0]), row.source)
    }

    @Test
    fun rawCountIsTheCountBeforeTheFilter() = runTest {
        val jobs = HomeCatalogs.jobs(listOf(addon), ContentRules(), emptySet())
        val res = HomeCatalogs.loadRows(AddonClient(http()), jobs, filter = { items -> items.take(1) })
        val row = res.rows.first()
        assertEquals(1, row.items.size)
        assertEquals(2, row.rawCount)
    }

    @Test
    fun progressReportsRowsAsTheyLandInStableOrder() = runTest {
        val two = Addon(
            "https://h.example/manifest.json",
            AddonManifest.parse(manifestJson("org.test", catalogs = """[{"type":"movie","id":"c1"},{"type":"movie","id":"c4"}]""")),
        )
        val seen = ArrayList<List<String>>()
        val jobs = HomeCatalogs.jobs(listOf(two), ContentRules(), emptySet())
        val res = HomeCatalogs.loadRows(AddonClient(http()), jobs, onProgress = { rows -> seen.add(rows.map { it.prefKey!! }) })
        assertEquals(2, res.rows.size)
        assertEquals(2, seen.size)
        assertEquals(listOf("addon:org.test/movie/c1", "addon:org.test/movie/c4"), seen.last())
        assertEquals(seen.last(), res.rows.map { it.prefKey })
    }

    @Test
    fun whenEveryRequestFailsTheResultSaysSo() = runTest {
        val jobs = HomeCatalogs.jobs(listOf(addon), ContentRules(), emptySet())
        val res = HomeCatalogs.loadRows(AddonClient(FakeHttp { reply("", 503) }), jobs)
        assertTrue(res.allFailed)
        assertTrue(res.rows.isEmpty())
    }

    @Test
    fun noJobsIsNotAFailure() = runTest {
        val res = HomeCatalogs.loadRows(AddonClient(FakeHttp { reply("", 503) }), emptyList())
        assertFalse(res.allFailed)
        assertEquals(0, res.jobs)
    }

    @Test
    fun failedJobsNamesTheCataloguesThatCouldNotBeFetched() = runTest {
        val jobs = HomeCatalogs.jobs(listOf(addon), ContentRules(), emptySet())
        val res = HomeCatalogs.loadRows(AddonClient(http()), jobs)
        assertEquals(listOf("c3"), res.failedJobs.map { it.second.id })
        assertEquals(res.failures, res.failedJobs.size)
    }

    @Test
    fun outcomeIsReportedOncePerJob() = runTest {
        val jobs = HomeCatalogs.jobs(listOf(addon), ContentRules(), emptySet())
        val seen = HashMap<Int, Boolean>()
        HomeCatalogs.loadRows(AddonClient(http()), jobs, onOutcome = { i, failed -> seen[i] = failed })
        assertEquals(jobs.size, seen.size)
        assertEquals(listOf("c3"), seen.filterValues { it }.keys.map { jobs[it].second.id })
    }
}

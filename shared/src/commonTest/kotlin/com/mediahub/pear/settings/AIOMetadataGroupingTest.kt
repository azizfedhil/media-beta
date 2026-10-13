package com.mediahub.pear.settings

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun cat(type: String, id: String, name: String? = null) =
    AddonManifest.CatalogDef(type = type, id = id, name = name)

private fun addon(url: String, name: String, catalogs: List<AddonManifest.CatalogDef> = emptyList()) =
    Addon(url, AddonManifest(id = "org.example.${name.lowercase()}", name = name, catalogs = catalogs))

class AIOMetadataGroupingTest {

    @Test
    fun movieAndSeriesHaveTheirOwnNamesAndOtherTypesAreCapitalised() {
        assertEquals("Movies", catalogKindLabel("movie"))
        assertEquals("Series", catalogKindLabel("series"))
        assertEquals("Anime", catalogKindLabel("anime"))
    }

    @Test
    fun catalogsAreGroupedByTypeInTheOrderTheAddOnListsThem() {
        val catalogs = listOf(
            cat("movie", "a"),
            cat("series", "b"),
            cat("movie", "c"),
            cat("series", "d"),
        )
        val groups = catalogGroupsMatching(catalogs, "")
        assertEquals(listOf("movie", "series"), groups.map { it.type })
        assertEquals(listOf("a", "c"), groups[0].catalogs.map { it.id })
        assertEquals(listOf("b", "d"), groups[1].catalogs.map { it.id })
    }

    @Test
    fun aSearchMatchesNamesAndIdsIgnoringCaseAndSurroundingSpaces() {
        val catalogs = listOf(
            cat("movie", "top", "Top Rated"),
            cat("movie", "trending.day", "Trending Today"),
            cat("series", "x.top", "Hidden"),
        )
        val matched = catalogGroupsMatching(catalogs, "  TOP ").flatMap { g -> g.catalogs.map { it.id } }
        assertEquals(listOf("top", "x.top"), matched)
    }

    @Test
    fun aCatalogWithoutANameIsFoundByItsId() {
        val catalogs = listOf(cat("movie", "popular.2026"), cat("movie", "other"))
        val matched = catalogGroupsMatching(catalogs, "2026").flatMap { g -> g.catalogs.map { it.id } }
        assertEquals(listOf("popular.2026"), matched)
    }

    @Test
    fun aQueryWithNoMatchGivesNoGroups() {
        val catalogs = listOf(cat("movie", "top", "Top Rated"))
        assertTrue(catalogGroupsMatching(catalogs, "zzz").isEmpty())
    }

    @Test
    fun aBlankQueryKeepsEverything() {
        val catalogs = listOf(cat("movie", "a"), cat("series", "b"))
        assertEquals(2, catalogGroupsMatching(catalogs, "   ").sumOf { it.catalogs.size })
    }

    @Test
    fun theHeadingShowsWhetherTheAddOnIsOffAndHowManyAreOnHome() {
        assertEquals("AIOMetadata · 3 on Home", manageHeader("AIOMetadata", enabled = true, on = 3))
        assertEquals("AIOMetadata (add-on is off) · 0 on Home", manageHeader("AIOMetadata", enabled = false, on = 0))
    }

    @Test
    fun anAddOnWithNoCataloguesSaysSo() {
        val empty = addon("https://addon.example/manifest.json", "AIOMetadata")
        assertTrue(manageFooter(empty).startsWith("This add-on offers no catalogues"))
    }

    @Test
    fun aCustomAddOnStaysListedHereWhetherOrNotItsPickerIsOn() {
        // The toggle for this list is what switches a hand-enabled add-on back off, so it must stay listed.
        val aio = addon("https://addon.example/manifest.json", "AIOMetadata", listOf(cat("movie", "a")))
        val custom = addon("https://custom.example/manifest.json", "Custom", listOf(cat("movie", "b")))
        val bare = addon("https://bare.example/manifest.json", "Bare")
        assertEquals(listOf(custom), manualAddonsOf(listOf(aio, custom, bare)))
    }
}

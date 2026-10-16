package com.mediahub.pear.shell

import com.mediahub.pear.model.CatalogSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShellStateTest {
    @Test
    fun startsOnHome() {
        val s = ShellState()
        assertEquals(AppTab.Home, s.tab)
        assertEquals(listOf(AppTab.Home), s.visited.toList())
    }

    @Test
    fun selectingTabsMarksThemVisitedOnce() {
        val s = ShellState()
        s.select(AppTab.Explore)
        s.select(AppTab.Home)
        s.select(AppTab.Explore)
        assertEquals(listOf(AppTab.Home, AppTab.Explore), s.visited.toList())
        assertEquals(AppTab.Explore, s.tab)
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        val s = ShellState()
        val home = s.navFor(AppTab.Home)
        home.push(Route.Demo(1))
        s.select(AppTab.Explore)
        s.select(AppTab.Home)
        assertSame(home, s.navFor(AppTab.Home))
        assertEquals(Route.Demo(1), s.navFor(AppTab.Home).top)
        assertEquals(Route.Root(AppTab.Explore), s.navFor(AppTab.Explore).top)
    }

    @Test
    fun cataloguePageReplacesTheTabUntilATabIsPicked() {
        val s = ShellState()
        val item = CatalogueItem("a/b", "Trending Movies")
        s.selectCatalogue(item)
        assertEquals(item, s.catalogue)
        assertEquals(Route.Catalogue("a/b", "Trending Movies"), s.currentNav().top)
        s.select(AppTab.Library)
        assertNull(s.catalogue)
        assertNull(s.catalogueNav)
    }

    @Test
    fun backPopsTheCurrentStackOnly() {
        val s = ShellState()
        assertFalse(s.back())
        s.navFor(AppTab.Home).push(Route.Demo(1))
        assertTrue(s.back())
        assertFalse(s.back())
    }

    @Test
    fun aSidebarCatalogueKeepsItsSourceSoItCanLoad() {
        val s = ShellState()
        val source = CatalogSource.TmdbTrending("movie")
        s.selectCatalogue(CatalogueItem("a/b", "Trending Movies", source))
        val row = (s.currentNav().top as Route.Catalogue).row
        assertEquals(source, row.source)
        assertTrue(row.items.isEmpty(), "the page fetches its own first page")
    }

    @Test
    fun backFromTheFirstPageOfASidebarCatalogueLeavesTheCatalogue() {
        val s = ShellState()
        s.select(AppTab.Library)
        s.selectCatalogue(CatalogueItem("a/b", "Trending Movies"))
        assertTrue(s.back())
        assertNull(s.catalogue)
        assertEquals(AppTab.Library, s.tab)
        assertFalse(s.back())
    }

    @Test
    fun escapeLeavesFullScreenBeforeItGoesBack() {
        val s = ShellState()
        s.navFor(AppTab.Home).push(Route.Demo(1))
        var fullscreen = true
        s.leaveWindowFullscreen = { if (fullscreen) { fullscreen = false; true } else false }
        assertTrue(s.escape())
        assertEquals(2, s.navFor(AppTab.Home).depth, "the first Escape only left full screen")
        assertTrue(s.escape())
        assertEquals(1, s.navFor(AppTab.Home).depth)
    }

    @Test
    fun theWindowsFullScreenIsLeftAfterThePlayersOwn() {
        val s = ShellState()
        val order = mutableListOf<String>()
        s.escapeHook = { order.add("player"); true }
        s.leaveWindowFullscreen = { order.add("window"); true }
        assertTrue(s.escape())
        assertEquals(listOf("player"), order)
    }
}

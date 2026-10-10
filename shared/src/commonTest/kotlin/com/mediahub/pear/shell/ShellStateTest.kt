package com.mediahub.pear.shell

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
}

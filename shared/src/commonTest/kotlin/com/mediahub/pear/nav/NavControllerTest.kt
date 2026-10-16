package com.mediahub.pear.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavControllerTest {
    @Test
    fun startsAtRoot() {
        val n = NavController("root")
        assertEquals("root", n.top)
        assertEquals(1, n.depth)
        assertFalse(n.canGoBack)
        assertFalse(n.pop())
        assertFalse(n.popToRoot())
    }

    @Test
    fun pushAddsAndPopMarksLeavingUntilFinished() {
        val n = NavController("root")
        n.push("a")
        n.push("b")
        assertEquals("b", n.top)
        assertEquals(3, n.depth)
        assertTrue(n.pop())
        // the top page is still in the list (sliding out) but no longer counts
        assertEquals("a", n.top)
        assertEquals(2, n.depth)
        assertEquals(3, n.entries.size)
        assertTrue(n.entries.last().leaving)
        n.finishLeaving(n.entries.last())
        assertEquals(2, n.entries.size)
    }

    @Test
    fun newPagesStartOffScreenAndTheRootStartsShown() {
        val n = NavController("root")
        assertEquals(1f, n.entries[0].progress)
        n.push("a")
        assertEquals(0f, n.entries[1].progress)
    }

    @Test
    fun pushingWhileAPageIsLeavingFinishesItFirst() {
        val n = NavController("root")
        n.push("a")
        n.pop()
        n.push("b")
        assertEquals(listOf("root", "b"), n.entries.map { it.key })
        assertTrue(n.entries.none { it.leaving })
    }

    @Test
    fun popToRootDropsTheMiddlePagesAndSlidesTheTopAway() {
        val n = NavController("root")
        n.push("a")
        n.push("b")
        n.push("c")
        assertTrue(n.popToRoot())
        assertEquals("root", n.top)
        assertEquals(listOf("root", "c"), n.entries.map { it.key })
        assertTrue(n.entries.last().leaving)
    }

    @Test
    fun rapidPopsLeaveOnePageEachAndNeverTheRoot() {
        val n = NavController("root")
        n.push("a")
        n.push("b")
        assertTrue(n.pop())
        assertTrue(n.pop())
        assertFalse(n.pop())
        assertEquals("root", n.top)
    }
}

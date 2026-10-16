package com.mediahub.pear.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutModeTest {
    @Test
    fun switchesAtTheBreakpoint() {
        assertEquals(LayoutMode.Compact, layoutModeFor(390.dp))
        assertEquals(LayoutMode.Compact, layoutModeFor(639.dp))
        assertEquals(LayoutMode.Expanded, layoutModeFor(640.dp))
        assertEquals(LayoutMode.Expanded, layoutModeFor(1280.dp))
    }

    @Test
    fun overrideWinsOverWidth() {
        assertEquals(LayoutMode.Compact, layoutModeFor(1280.dp, LayoutOverride.Compact))
        assertEquals(LayoutMode.Expanded, layoutModeFor(390.dp, LayoutOverride.Expanded))
        assertEquals(LayoutMode.Expanded, layoutModeFor(1280.dp, LayoutOverride.Auto))
    }

    @Test
    fun sidebarWidthsMatchTheIpadShell() {
        assertEquals(250.dp, sidebarWidthFor(1000.dp))
        assertEquals(250.dp, sidebarWidthFor(1920.dp))
        assertEquals(212.dp, sidebarWidthFor(999.dp))
        assertEquals(212.dp, sidebarWidthFor(780.dp))
        assertEquals(76.dp, sidebarWidthFor(779.dp))
        assertEquals(76.dp, sidebarWidthFor(640.dp))
    }

    @Test
    fun onlyTheNarrowSidebarIsIconOnly() {
        assertTrue(isIconOnlySidebar(76.dp))
        assertFalse(isIconOnlySidebar(212.dp))
        assertFalse(isIconOnlySidebar(250.dp))
    }

    @Test
    fun tabOrdersMatchSwift() {
        assertEquals(listOf(AppTab.Search, AppTab.Home, AppTab.Explore, AppTab.Settings, AppTab.Library), AppTab.sidebarOrder)
        assertEquals(listOf(AppTab.Home, AppTab.Explore, AppTab.Library, AppTab.Settings), AppTab.tabBarOrder)
        assertEquals(AppTab.entries.toSet(), AppTab.sidebarOrder.toSet())
    }
}

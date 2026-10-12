package com.mediahub.pear.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact = the iPhone layout (tab bar). Expanded = the iPad layout (sidebar). */
enum class LayoutMode { Compact, Expanded }

/** Dev override of the automatic choice, so either layout can be checked in any window size. */
enum class LayoutOverride(val label: String) {
    Auto("Auto"),
    Compact("Phone layout"),
    Expanded("Wide layout"),
}

/**
 * Where the layouts switch. The iPad uses the wide layout when its horizontal size class is regular (full screen
 * or a wide split view; Slide Over falls back to tabs). 640 dp is where a window starts to behave like that.
 */
object Breakpoints {
    val expandedMinWidth: Dp = 640.dp

    // Sidebar widths from SidebarShell in WideLayout.swift.
    val sidebarFullMinWindow: Dp = 1000.dp
    val sidebarMediumMinWindow: Dp = 780.dp
    val sidebarFull: Dp = 250.dp
    val sidebarMedium: Dp = 212.dp
    val sidebarIconsOnly: Dp = 76.dp
}

fun layoutModeFor(windowWidth: Dp, override: LayoutOverride = LayoutOverride.Auto): LayoutMode = when (override) {
    LayoutOverride.Compact -> LayoutMode.Compact
    LayoutOverride.Expanded -> LayoutMode.Expanded
    LayoutOverride.Auto -> if (windowWidth >= Breakpoints.expandedMinWidth) LayoutMode.Expanded else LayoutMode.Compact
}

/** Full sidebar from 1000 dp, narrower from 780 dp, icons only below that. */
fun sidebarWidthFor(windowWidth: Dp): Dp = when {
    windowWidth >= Breakpoints.sidebarFullMinWindow -> Breakpoints.sidebarFull
    windowWidth >= Breakpoints.sidebarMediumMinWindow -> Breakpoints.sidebarMedium
    else -> Breakpoints.sidebarIconsOnly
}

/**
 * How much bigger the wide layout draws things. Phone layout is always 1x. Used by episode cards, theme cards and the
 * home hero; keep them here so the three stay in step.
 */
object WideScale {
    /** Episode cards and theme carousel cards: 33% larger. */
    const val CARD = 1.33f

    /** The home hero (wide): 10% wider. */
    const val HERO_WIDTH = 1.10f
}

/** The icon-only sidebar has no titles, no clock and no catalogue list. */
fun isIconOnlySidebar(sidebarWidth: Dp): Boolean = sidebarWidth < 100.dp

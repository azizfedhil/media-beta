package com.mediahub.pear.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import com.mediahub.pear.nav.NavController

/** Where a page stack can go. Real screens replace the placeholders in Phase 3. */
sealed interface Route {
    data class Root(val tab: AppTab) : Route
    data class Catalogue(val id: String, val title: String) : Route
    data class Demo(val depth: Int) : Route
    data object ThemePreview : Route
    data object GlassGallery : Route
    data object StorageCheck : Route
    data object MetadataCheck : Route
    data object AddonsCheck : Route
    data object HistoryCheck : Route
    data object BackupCheck : Route
}

/** An add-on catalogue listed under "Catalogues" in the sidebar (real data arrives with Batch 2.3). */
class CatalogueItem(val id: String, val title: String)

/**
 * Which tab is selected, which have been visited, and each tab's own page stack. Visited tabs stay composed while
 * hidden, so every one keeps its stack and scroll position, as `SidebarShell` does in WideLayout.swift.
 */
class ShellState(initialTab: AppTab = AppTab.Home) {
    var tab: AppTab by mutableStateOf(initialTab)
        private set

    /** An add-on catalogue page is showing instead of a tab. */
    var catalogue: CatalogueItem? by mutableStateOf(null)
        private set

    var layoutOverride: LayoutOverride by mutableStateOf(LayoutOverride.Auto)

    /** Window size before the dev "phone window" toggle shrank it; null when the window is not shrunk. */
    var savedWindowSize: DpSize? by mutableStateOf(null)

    val visited = mutableStateListOf(initialTab)

    private val tabNavs = HashMap<AppTab, NavController<Route>>()

    /** Stack of the open catalogue page. A new one is made each time a catalogue is picked. */
    var catalogueNav: NavController<Route>? by mutableStateOf(null)
        private set

    fun navFor(tab: AppTab): NavController<Route> = tabNavs.getOrPut(tab) { NavController(Route.Root(tab)) }

    fun select(tab: AppTab) {
        catalogue = null
        catalogueNav = null
        this.tab = tab
        if (tab !in visited) visited.add(tab)
    }

    fun selectCatalogue(item: CatalogueItem) {
        catalogue = item
        catalogueNav = NavController(Route.Catalogue(item.id, item.title))
    }

    /** The stack the user is looking at. */
    fun currentNav(): NavController<Route> = catalogueNav ?: navFor(tab)

    /** Back one page in the current stack. False at its root. */
    fun back(): Boolean = currentNav().pop()
}

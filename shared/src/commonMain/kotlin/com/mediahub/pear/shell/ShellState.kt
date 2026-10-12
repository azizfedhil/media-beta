package com.mediahub.pear.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.nav.NavController

sealed interface Route {
    data class Root(val tab: AppTab) : Route
    data class Catalogue(val row: CatalogRow) : Route {
        constructor(id: String, title: String) : this(CatalogRow(id, title, emptyList()))
        val id: String get() = row.id
        val title: String get() = row.title
    }
    data class Demo(val depth: Int) : Route
    data class Detail(val item: MetaPreview, val startSeason: Int? = null, val startEpisode: Int? = null) : Route
    data class Player(val request: PlayRequest) : Route
    data object ProfileDetail : Route
    data object ContentSettings : Route
    data object Downloads : Route
    data object Integrations : Route
    data object MediaServers : Route
    data object AddonsSettings : Route
    data object AIOMetadataSettings : Route
    data object SubtitleSettings : Route
    data object PlayerControlsSettings : Route
    data object About : Route
    data object Onboarding : Route
    data object ThemePreview : Route
    data object GlassGallery : Route
    data object StorageCheck : Route
    data object MetadataCheck : Route
    data object AddonsCheck : Route
    data object HistoryCheck : Route
    data object BackupCheck : Route
    data object PlayerCheck : Route
}

class CatalogueItem(val id: String, val title: String)

class ShellState(initialTab: AppTab = AppTab.Home) {
    var tab: AppTab by mutableStateOf(initialTab)
        private set

    var catalogue: CatalogueItem? by mutableStateOf(null)
        private set

    var layoutOverride: LayoutOverride by mutableStateOf(LayoutOverride.Auto)
    var savedWindowSize: DpSize? by mutableStateOf(null)
    val visited = mutableStateListOf(initialTab)

    val feedCollapse = FeedCollapse()

    var heroAmbientColor: Color by mutableStateOf(Color(0xFF0F3B36))

    private val tabNavs = HashMap<AppTab, NavController<Route>>()

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

    fun currentNav(): NavController<Route> = catalogueNav ?: navFor(tab)
    fun back(): Boolean = currentNav().pop()
}
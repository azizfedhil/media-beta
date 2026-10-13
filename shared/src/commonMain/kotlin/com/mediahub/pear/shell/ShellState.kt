package com.mediahub.pear.shell

import com.mediahub.pear.history.EpisodeItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.CatalogSource
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
    data object P2PCheck : Route
}

/**
 * A catalogue listed in the sidebar. [source] says where its titles come from: the page opened from the sidebar has no
 * row to start from (Home's "See all" passes one), so it loads its first page from the source itself.
 */
class CatalogueItem(
    val id: String,
    val title: String,
    val source: CatalogSource = CatalogSource.None,
    val prefKey: String? = null,
)

/**
 * A source picker for one title or episode. [ShellOverlays] draws it over the whole app, in the glass layer, so it can
 * blur what is behind it. Drawn inside a page it would sit in the blur source and come out transparent.
 */
class SourcePickerRequest(
    val item: MetaPreview,
    val imdbId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val thumb: String?,
    val logo: String?,
    val episodeInfo: EpisodeItem? = null,
    val onSelect: (PlayRequest) -> Unit,
)

class ShellState(initialTab: AppTab = AppTab.Home) {
    var tab: AppTab by mutableStateOf(initialTab)
        private set

    var catalogue: CatalogueItem? by mutableStateOf(null)
        private set

    var layoutOverride: LayoutOverride by mutableStateOf(LayoutOverride.Auto)
    var savedWindowSize: DpSize? by mutableStateOf(null)
    val visited = mutableStateListOf(initialTab)

    val feedCollapse = FeedCollapse()

    /** Asked first when Escape is pressed. Returns true if it took the key (the player leaves full screen this way). */
    var escapeHook: (() -> Boolean)? = null

    /**
     * Set by the shell: if the window is full screen, leaves it and returns true. Asked after [escapeHook], so the player keeps
     * its own handling, but a window that is full screen with no player to say so (an old saved state, a player that was
     * closed) can still be left with Escape or the mouse's back button, whatever page is open.
     */
    var leaveWindowFullscreen: (() -> Boolean)? = null

    /** Escape and the mouse's back button: the player's full screen, then the window's, then back one page. */
    fun escape(): Boolean = escapeHook?.invoke() == true || leaveWindowFullscreen?.invoke() == true || back()

    var heroAmbientColor: Color by mutableStateOf(Color(0xFF0F3B36))

    /** The source picker, if one is open. */
    var sourcePicker: SourcePickerRequest? by mutableStateOf(null)
        private set

    fun openSourcePicker(request: SourcePickerRequest) {
        sourcePicker = request
    }

    fun closeSourcePicker() {
        sourcePicker = null
    }

    /** The long-press style menu of a poster, season or episode, if one is open. */
    var contextMenu: ContextMenuRequest? by mutableStateOf(null)
        private set

    fun openContextMenu(request: ContextMenuRequest) {
        contextMenu = request
    }

    fun closeContextMenu() {
        contextMenu = null
    }

    private val tabNavs = HashMap<AppTab, NavController<Route>>()

    var catalogueNav: NavController<Route>? by mutableStateOf(null)
        private set

    fun navFor(tab: AppTab): NavController<Route> = tabNavs.getOrPut(tab) { NavController(Route.Root(tab)) }

    fun select(tab: AppTab) {
        sourcePicker = null
        contextMenu = null
        catalogue = null
        catalogueNav = null
        this.tab = tab
        if (tab !in visited) visited.add(tab)
    }

    fun selectCatalogue(item: CatalogueItem) {
        catalogue = item
        catalogueNav = NavController(Route.Catalogue(CatalogRow(item.id, item.title, emptyList(), source = item.source, prefKey = item.prefKey)))
    }

    fun currentNav(): NavController<Route> = catalogueNav ?: navFor(tab)

    /**
     * Back: pops the current stack. A catalogue opened from the sidebar has nothing under it, so going back from its first
     * page returns to the tab that was open before it (the sidebar's catalogue is left).
     */
    fun back(): Boolean {
        if (currentNav().pop()) return true
        if (catalogue != null) {
            select(tab)
            return true
        }
        return false
    }
}
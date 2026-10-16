package com.mediahub.pear.shell

/**
 * The two history pages listed under the catalogues in the sidebar. They open the way a catalogue does (see
 * [ShellState.selectCatalogue]), and [RouteContent] draws them with their own screens in `history/HistoryPages.kt`.
 *
 * Their ids start with `local:`. Add-on catalogue ids are `addon/type/catalogue`, so the two can't collide.
 */
object HistoryShortcuts {
    const val CONTINUE_WATCHING_ID = "local:continue-watching"
    const val RECENTLY_WATCHED_ID = "local:recently-watched"

    val continueWatching = CatalogueItem(CONTINUE_WATCHING_ID, "Continue Watching")
    val recentlyWatched = CatalogueItem(RECENTLY_WATCHED_ID, "Recently Watched")
}

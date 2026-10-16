package com.mediahub.pear.home

/**
 * One block of the Home feed, in the order it is drawn. [Theme] and [Addon] carry their index: into the theme list, or
 * into the add-on rows that are still shown after the user has hidden some.
 */
sealed interface FeedSlot {
    /** Continue Watching. Only in the feed when [feedOrder] is told there is something to show. */
    data object Continue : FeedSlot

    /** The [index]-th themed collection of today. */
    data class Theme(val index: Int) : FeedSlot

    /** The suggested rows (trending, because you watched): all of them, in order. */
    data object Suggested : FeedSlot

    /** The MDBList rows: all of them, in order. */
    data object Lists : FeedSlot

    /** The [index]-th add-on catalogue row that is shown. */
    data class Addon(val index: Int) : FeedSlot
}

/**
 * The order of the Home feed, as iOS draws it (`HomeView.swift`, `feedRows`): Continue Watching; theme 0; the suggested
 * rows; theme 1; the MDBList rows; theme 2; then each add-on row, and after every second add-on row the next theme. Any
 * themes left over close the feed, so none is lost when there are few add-on rows.
 *
 * [themeCount] is how many themes may show: 0 when the built-in themes row is hidden. [addonRowCount] counts the add-on
 * rows after the user's hidden rows are removed, as iOS does. A theme exists when its index is below [themeCount].
 */
fun feedOrder(showContinue: Boolean, themeCount: Int, addonRowCount: Int): List<FeedSlot> {
    val out = mutableListOf<FeedSlot>()
    fun theme(index: Int) {
        if (index < themeCount) out.add(FeedSlot.Theme(index))
    }

    if (showContinue) out.add(FeedSlot.Continue)
    theme(0)
    out.add(FeedSlot.Suggested)
    theme(1)
    out.add(FeedSlot.Lists)
    theme(2)
    for (i in 0 until addonRowCount) {
        out.add(FeedSlot.Addon(i))
        if (i % 2 == 1) theme(3 + i / 2)
    }
    for (index in (3 + addonRowCount / 2) until themeCount) out.add(FeedSlot.Theme(index))
    return out
}

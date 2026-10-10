package com.mediahub.pear.shell

/** The five destinations, in the order of `AppTab.allCases` in WideLayout.swift. */
enum class AppTab(val title: String, val symbol: String) {
    Search("Search", "magnifyingglass"),
    Home("Home", "house.fill"),
    Explore("Explore", "safari"),
    Settings("Settings", "gearshape.fill"),
    Library("Library", "books.vertical.fill");

    companion object {
        /** Sidebar order (iPad layout). */
        val sidebarOrder: List<AppTab> = listOf(Search, Home, Explore, Settings, Library)

        /** Phone tab bar order. Search is not in it: it is the separate round button next to the bar. */
        val tabBarOrder: List<AppTab> = listOf(Home, Explore, Library, Settings)
    }
}

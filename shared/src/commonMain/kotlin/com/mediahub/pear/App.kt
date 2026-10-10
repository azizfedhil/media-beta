package com.mediahub.pear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.shell.AdaptiveShell
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.CatalogueItem
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalWindowControl
import com.mediahub.pear.shell.ShellState
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore

/** Stand-ins for the add-on catalogues listed in the sidebar, until Batch 2.3 loads real ones. */
private val devCatalogues = listOf(
    "Trending Movies", "Trending Series", "Top Rated Movies", "Popular Series", "New Releases", "Anime Movies",
    "Documentaries", "Kids and Family", "Award Winners", "Staff Picks",
).mapIndexed { i, title -> CatalogueItem("dev/$i", title) }

/**
 * Root of the shared UI: theme, glass settings and the adaptive shell (sidebar on wide windows, tab bar on narrow).
 * [windowControl] lets the dev tools resize the window; [devTools] shows the developer section in Settings.
 */
@Composable
fun App(windowControl: WindowControl? = null, devTools: Boolean = true) {
    val theme = remember { ThemeStore() }
    val glass = remember { GlassStore() }
    val shell = remember { ShellState() }
    val env = remember { AppEnv(theme, glass, shell, devTools) }
    PearTheme(theme) {
        CompositionLocalProvider(LocalAppEnv provides env, LocalWindowControl provides windowControl) {
            AdaptiveShell(shell, glass, catalogues = if (devTools) devCatalogues else emptyList())
        }
    }
}

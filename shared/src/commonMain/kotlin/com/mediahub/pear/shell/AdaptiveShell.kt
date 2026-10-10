package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.nav.NavHost
import com.mediahub.pear.theme.PearTheme

/**
 * The app frame. Expanded windows get the sidebar layout, narrow ones the phone tab bar; both show the same
 * pages. Resize the window across 640 dp (or use the dev override in Settings) to see it switch.
 */
@Composable
fun AdaptiveShell(
    shell: ShellState,
    glass: GlassStore,
    catalogues: List<CatalogueItem>,
    modifier: Modifier = Modifier,
) {
    val c = PearTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        modifier.fillMaxSize().background(c.background)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { handleShellKey(it, shell) },
    ) {
        val mode = layoutModeFor(maxWidth, shell.layoutOverride)
        CompositionLocalProvider(LocalWideLayout provides (mode == LayoutMode.Expanded)) {
            if (mode == LayoutMode.Expanded) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(sidebarWidthFor(maxWidth), shell, catalogues, profileName = "Pear")
                    ShellPages(shell, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                CompositionLocalProvider(LocalBottomInset provides (TabBarHeight + 24.dp)) {
                    GlassScene(glass, Modifier.fillMaxSize(), background = { ShellPages(shell, Modifier.fillMaxSize()) }) {
                        CompactTabBar(shell, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/**
 * Keyboard: Alt+Left or Esc goes back, Ctrl+1..4 switch tabs, Ctrl+F opens Search.
 * (Media keys and the player's shortcuts come in Batch 4.6.)
 */
internal fun handleShellKey(event: KeyEvent, shell: ShellState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isAltPressed && event.key == Key.DirectionLeft) return shell.back()
    if (event.key == Key.Escape) return shell.back()
    if (event.isCtrlPressed) {
        val tab = when (event.key) {
            Key.One -> AppTab.Home
            Key.Two -> AppTab.Explore
            Key.Three -> AppTab.Library
            Key.Four -> AppTab.Settings
            Key.F -> AppTab.Search
            else -> null
        }
        if (tab != null) {
            shell.select(tab)
            return true
        }
    }
    return false
}

/** All visited pages, stacked. Only the selected one is measured, drawn and clickable; the rest keep their state. */
@Composable
internal fun ShellPages(shell: ShellState, modifier: Modifier = Modifier) {
    val catalogue = shell.catalogue
    val catalogueNav = shell.catalogueNav
    Box(modifier) {
        AppTab.entries.forEach { tab ->
            if (tab in shell.visited) {
                key(tab) {
                    val shown = catalogue == null && shell.tab == tab
                    KeepAlive(shown) {
                        CompositionLocalProvider(LocalPageActive provides shown) {
                            val nav = shell.navFor(tab)
                            NavHost(nav) { route -> RouteContent(route, nav) }
                        }
                    }
                }
            }
        }
        if (catalogue != null && catalogueNav != null) {
            key(catalogue.id) {
                NavHost(catalogueNav) { route -> RouteContent(route, catalogueNav) }
            }
        }
    }
}

/** Composed always, but laid out (and so drawn and hit-tested) only while [shown]. */
@Composable
private fun KeepAlive(shown: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().layout { measurable, constraints ->
            if (shown) {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            }
        },
    ) { content() }
}

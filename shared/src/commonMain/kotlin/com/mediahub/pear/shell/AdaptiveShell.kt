package com.mediahub.pear.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.nav.NavHost
import com.mediahub.pear.profile.ProfileAvatarButton
import com.mediahub.pear.profile.ProfileSheetOverlay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.theme.PearTheme

@Composable
fun AdaptiveShell(
    shell: ShellState,
    glass: GlassStore,
    catalogues: List<CatalogueItem>,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0xFF09090B))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { handleShellKey(it, shell) },
    ) {
        val windowWidth = maxWidth
        val mode = layoutModeFor(windowWidth, shell.layoutOverride)
        // "Who's watching?" is opened from the avatar (sidebar in the wide layout, top corner in the phone layout).
        var showProfiles by remember { mutableStateOf(false) }
        // The player takes the whole window: no sidebar or tab bar over the picture.
        val playerOnTop = shell.currentNav().top is Route.Player

        // Ambient glow background derived from the hero poster
        val animatedAmbient by animateColorAsState(shell.heroAmbientColor, tween(1800))

        CompositionLocalProvider(LocalWideLayout provides (mode == LayoutMode.Expanded)) {
            if (mode == LayoutMode.Expanded) {
                // Entire expanded window shares one GlassScene so the ambient glow bleeds through the sidebar
                GlassScene(
                    store = glass,
                    modifier = Modifier.fillMaxSize(),
                    background = {
                        // One soft colour wash over the whole page: strongest behind the hero, dissolving to near-black.
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                val c = animatedAmbient
                                drawRect(
                                    Brush.radialGradient(
                                        0f to c.copy(alpha = 0.75f),
                                        0.40f to c.copy(alpha = 0.38f),
                                        0.75f to c.copy(alpha = 0.12f),
                                        1f to Color.Transparent,
                                        center = Offset(size.width * 0.30f, size.height * 0.38f),
                                        radius = maxOf(size.width, size.height) * 1.0f,
                                    ),
                                )
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to c.copy(alpha = 0.20f),
                                        0.55f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.55f),
                                    ),
                                )
                            },
                        )
                    },
                ) {
                    Row(Modifier.fillMaxSize()) {
                        // Home's feed folds the sidebar down to icons as it is scrolled or dragged.
                        val homeShown = shell.tab == AppTab.Home && shell.catalogue == null
                        val fold by animateFloatAsState(if (homeShown) shell.feedCollapse.progress else 0f, tween(140))
                        if (!playerOnTop) Sidebar(
                            width = lerp(sidebarWidthFor(windowWidth), Breakpoints.sidebarIconsOnly, fold),
                            forceIcons = fold > 0.45f,
                            shell = shell,
                            catalogues = catalogues,
                            profile = env.data.profiles.active,
                            onProfileClick = { showProfiles = true },
                        )
                        ShellPages(shell, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                CompositionLocalProvider(LocalBottomInset provides (TabBarHeight + 24.dp)) {
                    GlassScene(glass, Modifier.fillMaxSize(), background = { ShellPages(shell, Modifier.fillMaxSize()) }) {
                        if (!playerOnTop) CompactTabBar(shell, Modifier.align(Alignment.BottomCenter))
                        if (!playerOnTop) ProfileAvatarButton(
                            profile = env.data.profiles.active,
                            onClick = { showProfiles = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                            size = 34.dp,
                        )
                    }
                }
            }
        }
        if (showProfiles) {
            ProfileSheetOverlay(
                onDismiss = { showProfiles = false },
                onOpenProfileDetail = {
                    showProfiles = false
                    shell.currentNav().push(Route.ProfileDetail)
                },
                onOpenDownloads = {
                    showProfiles = false
                    shell.currentNav().push(Route.Downloads)
                },
            )
        }
    }
}

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
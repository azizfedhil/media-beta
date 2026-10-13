package com.mediahub.pear.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.mediahub.pear.detail.SourcePickerOverlay
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.nav.NavHost
import com.mediahub.pear.profile.ProfileAvatarButton
import com.mediahub.pear.profile.ProfileSheetOverlay
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.PearTransitions

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

    val window = LocalWindowControl.current
    DisposableEffect(shell, window) {
        shell.leaveWindowFullscreen = {
            if (window != null && window.mode == WindowMode.Fullscreen) {
                window.mode = WindowMode.Maximized
                true
            } else {
                false
            }
        }
        onDispose { shell.leaveWindowFullscreen = null }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0xFF09090B))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { handleShellKey(it, shell) }
            .mouseBack(shell),
    ) {
        val windowWidth = maxWidth
        val mode = layoutModeFor(windowWidth, shell.layoutOverride)
        val playerOnTop = shell.currentNav().top is Route.Player

        val topRoute = shell.currentNav().top
        val heroPage = topRoute is Route.Detail
        val targetAmbient = if (shell.heroAmbientKnown) shell.heroAmbientColor else PearTheme.colors.accent
        val animatedAmbient by animateColorAsState(targetAmbient, tween(900, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)))
        val ambientStrength by animateFloatAsState(if (heroPage) 1f else 0f, tween(1800))

        CompositionLocalProvider(LocalWideLayout provides (mode == LayoutMode.Expanded)) {
            if (mode == LayoutMode.Expanded) {
                val homeShown = shell.tab == AppTab.Home && shell.catalogue == null
                val fold by animateFloatAsState(if (homeShown) shell.feedCollapse.progress else 0f, tween(140))
                val sidebarW = lerp(sidebarWidthFor(windowWidth), Breakpoints.sidebarIconsOnly, fold)
                GlassScene(
                    store = glass,
                    modifier = Modifier.fillMaxSize(),
                    background = {
                        AmbientWash(animatedAmbient, ambientStrength)
                        ShellPages(shell, Modifier.fillMaxSize().padding(start = if (playerOnTop) 0.dp else sidebarW))
                    },
                ) {
                    if (!playerOnTop) Sidebar(
                        modifier = Modifier.align(Alignment.TopStart),
                        width = sidebarW,
                        forceIcons = fold > 0.45f,
                        shell = shell,
                        catalogues = catalogues,
                        profile = env.data.profiles.active,
                        onProfileClick = { shell.showProfiles = true },
                    )
                    ShellOverlays(shell, shell.showProfiles, sidebarWidth = sidebarW) { shell.showProfiles = it }
                }
            } else {
                CompositionLocalProvider(LocalBottomInset provides (TabBarHeight + 24.dp)) {
                    GlassScene(
                        glass,
                        Modifier.fillMaxSize(),
                        background = {
                            AmbientWash(animatedAmbient, ambientStrength)
                            ShellPages(shell, Modifier.fillMaxSize())
                        },
                    ) {
                        if (!playerOnTop) CompactTabBar(shell, Modifier.align(Alignment.BottomCenter))
                        ShellOverlays(shell, shell.showProfiles) { shell.showProfiles = it }
                        val isSubPage = shell.currentNav().canGoBack
                        if (!playerOnTop && !isSubPage) ProfileAvatarButton(
                            profile = env.data.profiles.active,
                            onClick = { shell.showProfiles = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                            size = 34.dp,
                        )
                    }
                }
            }
        }
    }
}

internal fun handleShellKey(event: KeyEvent, shell: ShellState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isAltPressed && event.key == Key.DirectionLeft) return shell.back()
    if (event.key == Key.Escape) {
        return shell.escape()
    }
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
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(PearMotion.BASE, easing = PearMotion.standard))
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade }.layout { measurable, constraints ->
            if (shown || fade > 0f) {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            }
        },
    ) { content() }
}

@Composable
private fun AmbientWash(color: Color, strength: Float) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val fadeHeight = minOf(size.height, 1100.dp.toPx())
            drawRect(
                brush = Brush.verticalGradient(
                    0f to color.copy(alpha = 0.70f * strength),
                    0.5f to color.copy(alpha = 0.25f * strength),
                    1f to Color.Transparent,
                ),
                size = Size(size.width, fadeHeight),
            )
        },
    )
}

@Composable
private fun ShellOverlays(
    shell: ShellState,
    showProfiles: Boolean,
    sidebarWidth: Dp? = null,
    onProfiles: (Boolean) -> Unit,
) {
    val wide = LocalWideLayout.current
    val panelIn = if (wide) PearTransitions.overlayIn else PearTransitions.sheetIn
    val panelOut = if (wide) PearTransitions.overlayOut else PearTransitions.sheetOut
    val picker = retainedWhilePresent(shell.sourcePicker)
    val menu = retainedWhilePresent(shell.contextMenu)

    AnimatedVisibility(visible = showProfiles, enter = panelIn, exit = panelOut) {
        ProfileSheetOverlay(
            sidebarWidth = sidebarWidth,
            onDismiss = { onProfiles(false) },
            onOpenProfileDetail = {
                onProfiles(false)
                shell.currentNav().push(Route.ProfileDetail)
            },
            onOpenDownloads = {
                onProfiles(false)
                shell.currentNav().push(Route.Downloads)
            },
            onOpenSimkl = {
                onProfiles(false)
                shell.currentNav().push(Route.SimklLogin)
            },
        )
    }
    AnimatedVisibility(visible = shell.sourcePicker != null, enter = panelIn, exit = panelOut) {
        picker?.let { SourcePickerOverlay(it, onClose = { shell.closeSourcePicker() }) }
    }
    AnimatedVisibility(
        visible = shell.contextMenu != null,
        enter = PearTransitions.overlayIn,
        exit = PearTransitions.overlayOut,
    ) {
        menu?.let { ContextMenuOverlay(it, onClose = { shell.closeContextMenu() }) }
    }
}

@Composable
private fun <T : Any> retainedWhilePresent(current: T?): T? {
    val held = remember { RetainedValue<T>() }
    if (current != null) held.value = current
    return held.value
}

private class RetainedValue<T> {
    var value: T? = null
}
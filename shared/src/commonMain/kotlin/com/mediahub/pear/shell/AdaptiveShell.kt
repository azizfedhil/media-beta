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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.nav.NavHost
import com.mediahub.pear.profile.ProfileAvatarButton
import com.mediahub.pear.profile.ProfileSheetOverlay
import com.mediahub.pear.detail.SourcePickerOverlay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.theme.PearTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.graphicsLayer
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearTransitions
import androidx.compose.animation.core.CubicBezierEasing

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

    // The way out of a full-screen window that no player is holding: Escape (or the back button) leaves it, on any page.
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
        // "Who's watching?" is opened from the avatar (sidebar in the wide layout, top corner in the phone layout).
        var showProfiles by remember { mutableStateOf(false) }
        // The player takes the whole window: no sidebar or tab bar over the picture.
        val playerOnTop = shell.currentNav().top is Route.Player

        // The fixed glow behind the page is for a title's detail page only. Home draws its own, inside its scrolling content
        // (`attachedAmbient`), as iOS does, so the sidebar is never tinted by it. Elsewhere the glow fades out.
        val topRoute = shell.currentNav().top
        val heroPage = topRoute is Route.Detail
        val animatedAmbient by animateColorAsState(shell.heroAmbientColor, tween(900, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)))
        val ambientStrength by animateFloatAsState(if (heroPage) 1f else 0f, tween(1800))

        CompositionLocalProvider(LocalWideLayout provides (mode == LayoutMode.Expanded)) {
            if (mode == LayoutMode.Expanded) {
                // Home's feed folds the sidebar down to icons as it is scrolled or dragged.
                val homeShown = shell.tab == AppTab.Home && shell.catalogue == null
                val fold by animateFloatAsState(if (homeShown) shell.feedCollapse.progress else 0f, tween(140))
                val sidebarW = lerp(sidebarWidthFor(windowWidth), Breakpoints.sidebarIconsOnly, fold)
                // Entire expanded window shares one GlassScene so the ambient glow bleeds through the sidebar. The pages
                // are its backdrop, starting right of the sidebar, as in the phone layout: the glass that floats over
                // them (the profile panel, the source picker) blurs the pages, and the sidebar keeps its look.
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
                        onProfileClick = { showProfiles = true },
                    )
                    ShellOverlays(shell, showProfiles, sidebarWidth = sidebarW) { showProfiles = it }
                }
            } else {
                CompositionLocalProvider(LocalBottomInset provides (TabBarHeight + 24.dp)) {
                    GlassScene(glass, Modifier.fillMaxSize(), background = { AmbientWash(animatedAmbient, ambientStrength); ShellPages(shell, Modifier.fillMaxSize()) }) {
                        if (!playerOnTop) CompactTabBar(shell, Modifier.align(Alignment.BottomCenter))
                        ShellOverlays(shell, showProfiles) { showProfiles = it }
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
    // The page fades in and out with the tab switch, and keeps its room until the fade-out ends. The fade is read in the
    // layout and draw phases, so the page itself does not recompose while it moves.
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

/**
 * The ambient glow, drawn as iOS draws it on Home (HomeView's `ambient`): the hero's colour from the top at 70%, easing to 25%
 * at the middle and to clear at 1100 dp. [strength] (0 to 1) fades it in and out between pages.
 */
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

/**
 * What floats over the app: "Who's watching?", the source picker and the context menu. Drawn inside the glass scene, so
 * their glass blurs the app behind them, in both layouts. Each one fades in and out. The sheets rise from the bottom in
 * the phone layout and grow in the wide one. A request is held while it leaves, so it can animate out after it is cleared.
 */
@Composable
private fun ShellOverlays(
    shell: ShellState,
    showProfiles: Boolean,
    /** The wide layout's sidebar width; the profile panel opens beside it. Null in the phone layout. */
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

/** Holds the last non-null [current], so an overlay still has its content while it animates out. */
@Composable
private fun <T : Any> retainedWhilePresent(current: T?): T? {
    val held = remember { RetainedValue<T>() }
    if (current != null) held.value = current
    return held.value
}

private class RetainedValue<T> {
    var value: T? = null
}

package com.mediahub.pear.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.OfflineHttp
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.theme.ThemeStore

val LocalAppEnv = staticCompositionLocalOf<AppEnv> { error("AppEnv not provided: wrap the UI in App()") }

val LocalHttp = staticCompositionLocalOf<Http> { OfflineHttp }

val LocalImageCache = staticCompositionLocalOf<ResponseCache> { ResponseCache.None }

/** True inside the expanded (sidebar) layout. Views use it to pick bigger posters, skip the avatar button, etc. */
val LocalWideLayout = compositionLocalOf { false }

/** The sidebar's width in the expanded layout, 0 elsewhere (and while a film is on top, where the sidebar is hidden). */
val LocalSidebarWidth = compositionLocalOf { 0.dp }

/** True when the sidebar is drawn as glass, so a page may draw behind it (see `RouteContent` in Pages.kt). */
val LocalSidebarGlass = compositionLocalOf { false }

/** Above 0 only on a page drawn under a glass sidebar: how far that sidebar covers the page's left edge. */
val LocalSidebarInset = compositionLocalOf { 0.dp }

/** Space the shell covers at the bottom of the page (the phone tab bar). Scroll content pads by this much. */
val LocalBottomInset = compositionLocalOf { 0.dp }

/** Height of the phone tab bar and of the round search button beside it. */
val TabBarHeight: Dp = 64.dp

class AppEnv(
    val theme: ThemeStore,
    val glass: GlassStore,
    val shell: ShellState,
    /** Settings, profiles, content rules and secrets (Phase 2). */
    val data: AppData,
    /** Build flag. No longer shows the Developer section: that is the About switch (PrefKeys.DEV_TOOLS). Nothing else reads it. */
    val devTools: Boolean,
)
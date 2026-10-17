package com.mediahub.pear.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.OfflineHttp
import com.mediahub.pear.theme.ThemeStore

val LocalAppEnv = staticCompositionLocalOf<AppEnv> { error("AppEnv not provided: wrap the UI in App()") }

val LocalHttp = staticCompositionLocalOf<Http> { OfflineHttp }

/** True inside the expanded (sidebar) layout. Views use it to pick bigger posters, skip the avatar button, etc. */
val LocalWideLayout = compositionLocalOf { false }

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
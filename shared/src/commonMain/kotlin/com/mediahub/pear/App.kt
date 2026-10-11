package com.mediahub.pear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.Storage
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.net.Network
import com.mediahub.pear.shell.AdaptiveShell
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.CatalogueItem
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalWindowControl
import com.mediahub.pear.shell.ShellState
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore

/**
 * Root of the shared UI: theme, glass settings and the adaptive shell (sidebar on wide windows, tab bar on narrow).
 * [windowControl] lets the dev tools resize the window; [devTools] shows the developer section in Settings.
 * [storage] is the platform's settings and secret storage; without it nothing is saved (previews, tests).
 * [network] is the platform's HTTP and disk caches; without it nothing leaves the machine.
 */
@Composable
fun App(windowControl: WindowControl? = null, devTools: Boolean = true, storage: Storage? = null, network: Network? = null) {
    val data = remember(storage, network) { AppData(storage ?: Storage.inMemory(), network ?: Network.offline()) }
    DisposableEffect(data) { onDispose { data.close() } }
    val theme = remember(data) { ThemeStore(prefs = data.prefs) }
    val glass = remember(data) { GlassStore(prefs = data.prefs) }
    val shell = remember { ShellState() }
    val env = remember(data) { AppEnv(theme, glass, shell, data, devTools) }
    // Each profile has its own content settings, history and library: follow the active profile.
    LaunchedEffect(data.profiles.activeID) { data.switchProfile(data.profiles.activeID) }
    PearTheme(theme) {
        CompositionLocalProvider(
            LocalAppEnv provides env,
            LocalWindowControl provides windowControl,
            com.mediahub.pear.shell.LocalHttp provides data.network.http
        ) {
            AdaptiveShell()
        }
    }
}

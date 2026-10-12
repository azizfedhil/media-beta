package com.mediahub.pear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.brand.PearLaunchOverlay
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.Storage
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.net.Network
import com.mediahub.pear.shell.AdaptiveShell
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.CatalogueItem
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.LocalWindowControl
import com.mediahub.pear.shell.ShellState
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore

@Composable
fun App(windowControl: WindowControl? = null, devTools: Boolean = true, storage: Storage? = null, network: Network? = null) {
    val data = remember(storage, network) { AppData(storage ?: Storage.inMemory(), network ?: Network.offline()) }
    DisposableEffect(data) { onDispose { data.close() } }
    val theme = remember(data) { ThemeStore(prefs = data.prefs) }
    val glass = remember(data) { GlassStore(prefs = data.prefs) }
    val shell = remember { ShellState() }
    val env = remember(data) { AppEnv(theme, glass, shell, data, devTools) }
    LaunchedEffect(data.profiles.activeID) { data.switchProfile(data.profiles.activeID) }
    PearTheme(theme) {
        CompositionLocalProvider(
            LocalAppEnv provides env,
            LocalWindowControl provides windowControl,
            LocalHttp provides data.network.http,
        ) {
            val catalogues = HomeCatalogs.jobs(data.addons.enabledAddons, data.content.rules, data.addons.pickerIds)
                .map { (addon, cat) -> CatalogueItem("${addon.id}/${cat.type}/${cat.id}", HomeCatalogs.rowTitle(cat)) }
            // The intro plays over the app while it loads, as the iOS launch screen does.
            Box(Modifier.fillMaxSize()) {
                AdaptiveShell(shell, glass, catalogues = catalogues)
                PearLaunchOverlay()
            }
        }
    }
}
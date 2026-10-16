package com.mediahub.pear

import com.mediahub.pear.shell.AppExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.brand.PearLaunchOverlay
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.Storage
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.onboarding.OnboardingHost
import com.mediahub.pear.p2p.blockingUntil
import com.mediahub.pear.onboarding.OnboardingState
import com.mediahub.pear.net.Network
import com.mediahub.pear.shell.AdaptiveShell
import com.mediahub.pear.shell.CrashBriefingHost
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.CatalogueItem
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.LocalWindowControl
import com.mediahub.pear.shell.ShellState
import com.mediahub.pear.shell.SystemBackHandler
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore
import kotlinx.coroutines.delay

@Composable
fun App(windowControl: WindowControl? = null, devTools: Boolean = true, storage: Storage? = null, network: Network? = null, overlay: @Composable BoxScope.() -> Unit = {}) {
    val data = remember(storage, network) { AppData(storage ?: Storage.inMemory(), network ?: Network.offline()) }
    DisposableEffect(data) { onDispose { data.close() } }
    val theme = remember(data) { ThemeStore(prefs = data.prefs) }
    val glass = remember(data) { GlassStore(prefs = data.prefs) }
    val shell = remember { ShellState() }
    // Decided once per storage: a fresh install sees the setup first, an existing one never does (see OnboardingState).
    val setupNeeded = remember(data) {
        OnboardingState.needed(data.prefs, data.secrets, hasSimklAccount = data.simkl.account != null)
    }
    LaunchedEffect(setupNeeded) { shell.onboardingVisible = setupNeeded }
    val env = remember(data) { AppEnv(theme, glass, shell, data, devTools) }
    // The system back action (Android's back button and gesture) closes the topmost page or the player. The system
    // takes it only when nothing is open.
    SystemBackHandler { env.shell.escape() }
    LaunchedEffect(data.profiles.activeID) { data.switchProfile(data.profiles.activeID) }
    // Progress made in other apps comes back from Simkl: once when Simkl connects or the profile changes, then every five minutes.
    LaunchedEffect(data.simkl.isConnected, data.profiles.activeID) {
        data.simklHistory.pull(data.profiles.activeID, force = true)
        while (true) {
            delay(5 * 60_000L)
            data.simklHistory.pull(data.profiles.activeID)
        }
    }
    // The P2P engine ends with the window: the close runs this hook before the process exits, and waits for the stop (4.5d).
    DisposableEffect(data) {
        val unregister = AppExit.register { data.p2p.engine.stopBlocking() }
        // The last Simkl stop of a film is sent in the background; the close waits for it (for a short while) so it is not lost.
        val unregisterScrobbles = AppExit.register { blockingUntil(2500) { data.simkl.awaitPendingScrobbles(2500) } }
        onDispose {
            unregister()
            unregisterScrobbles()
        }
    }
    // Leftovers of an earlier crash or kill go at launch, whether or not P2P is on: nothing of P2P stays on disk between runs.
    LaunchedEffect(data) { data.p2p.engine.removeLeftovers() }
    PearTheme(theme) {
        CompositionLocalProvider(
            LocalAppEnv provides env,
            LocalWindowControl provides windowControl,
            LocalHttp provides data.network.http,
        ) {
            val catalogues = HomeCatalogs.jobs(data.addons.enabledAddons, data.content.rules, data.addons.pickerIds)
                .map { (addon, cat) ->
                    CatalogueItem(
                        id = "${addon.id}/${cat.type}/${cat.id}",
                        title = HomeCatalogs.rowTitle(cat),
                        source = CatalogSource.FromAddon(addon, cat),
                        prefKey = addon.rowKey(cat),
                    )
                }
            // The intro plays over the app while it loads, as the iOS launch screen does.
            Box(Modifier.fillMaxSize()) {
                AdaptiveShell(shell, glass, catalogues = catalogues)
                // The first-launch setup covers the shell while it is pending; the launch intro still plays above it.
                if (shell.onboardingVisible) OnboardingHost()
                // Platform extras that sit over the app, such as the desktop's "new version" notice.
                overlay()
                PearLaunchOverlay()
                // The crash briefing, from the last run, sits above everything (it shows nothing when there is none).
                CrashBriefingHost()
            }
        }
    }
}

package com.mediahub.pear

import com.mediahub.pear.shell.AppExit
import com.mediahub.pear.shell.AppLifecycle
import com.mediahub.pear.shell.PerformanceNotice
import com.mediahub.pear.shell.PlatformCaps
import com.mediahub.pear.shell.Route
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.brand.PearLaunchOverlay
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.Storage
import com.mediahub.pear.glass.FramePacing
import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.glass.PowerStatus
import com.mediahub.pear.log.PearTrace
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
import com.mediahub.pear.theme.NoIndication
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun App(
    windowControl: WindowControl? = null,
    devTools: Boolean = true,
    storage: Storage? = null,
    network: Network? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Runs once, on a background thread, after the launch intro (desktop loads the player there; Android passes nothing). */
    warmPlayer: () -> Unit = {},
) {
    val data = remember(storage, network) {
        AppData(storage ?: Storage.inMemory(), network ?: Network.offline(), autoStartAddons = false)
    }
    DisposableEffect(data) { onDispose { data.close() } }
    val theme = remember(data) { ThemeStore(prefs = data.prefs) }
    // A setting that syncs from another device can change the accent: the theme re-reads it (see AppData.reloadAfterSettings).
    DisposableEffect(data, theme) {
        data.onThemeSettingsSynced = { theme.reload() }
        onDispose { data.onThemeSettingsSynced = {} }
    }
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
    // Android: when the app runs slowly (under 55 fps, or it hangs), the effects drop one tier and the person is told.
    var perfNote by remember { mutableStateOf<String?>(null) }
    if (PlatformCaps.hasPlayerGlassChoice) {
        val playerOpen = shell.currentNav().top is Route.Player
        LaunchedEffect(playerOpen, shell.onboardingVisible) {
            FramePacing.enabled = !playerOpen && !shell.onboardingVisible
            FramePacing.rest(8_000)
        }
        LaunchedEffect(glass) {
            FramePacing.slow.collect {
                if (PowerStatus.lowBattery) return@collect
                val to = glass.stepDown() ?: return@collect
                FramePacing.rest(20_000)
                val name = when (to) {
                    GlassMode.Frosted -> "Frosted Blur"
                    GlassMode.Flat -> "Flat"
                    else -> "Liquid Glass"
                }
                perfNote = "The app was running slowly, so the effects were changed to $name. You can change this in Settings > Appearance."
            }
        }
        LaunchedEffect(perfNote) {
            if (perfNote != null) {
                delay(6_000)
                perfNote = null
            }
        }
    }
    // The launch restore of the saved add-ons is started here, after the first composition has been applied. Saved
    // add-ons that missed the launch fetch (no connection yet) are asked for again at launch and every time the app comes back to the front.
    LaunchedEffect(data) {
        data.addons.start()
        data.addons.retryUnreachable()
        AppLifecycle.foregrounded.collect { data.addons.retryUnreachable() }
    }
    // UI frame-clock probe, only while tracing: the gap between frames is measured, so a stall in the UI shows in the trace.
    // It runs for the whole session: the launch and the source picker are measured as well as the player.
    // While tracing, Compose asks for a frame every vsync. That is a known, accepted side effect of this probe.
    val traceOn by PearTrace.enabledFlow.collectAsState()
    if (traceOn) LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val t = withFrameNanos { it }
            if (last != 0L) {
                val gapMs = (t - last) / 1_000_000.0
                PearTrace.count("ui.frames")
                PearTrace.sample("ui.frameGapMs", gapMs)
                if (gapMs > 40.0) PearTrace.event("UI") { "frame gap ${gapMs.toLong()} ms" }
            }
            last = t
        }
    }
    // The player's native library and classes are loaded after the launch intro, off the UI thread, so its first open does not
    // stall (see PlayerWarmup on desktop).
    LaunchedEffect(Unit) {
        delay(6_000L)
        withContext(Dispatchers.Default) { warmPlayer() }
    }
    PearTheme(theme) {
        CompositionLocalProvider(
            LocalAppEnv provides env,
            LocalWindowControl provides windowControl,
            LocalHttp provides data.network.http,
            LocalIndication provides NoIndication,
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
                PerformanceNotice(perfNote)
                PearLaunchOverlay()
                // The crash briefing, from the last run, sits above everything (it shows nothing when there is none).
                CrashBriefingHost()
            }
        }
    }
}

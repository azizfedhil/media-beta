package com.mediahub.pear.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.PlaybackProgress
import com.mediahub.pear.player.PlaybackSnapshot
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.ProgressRules
import com.mediahub.pear.player.VideoSurface
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.player.withSource
import com.mediahub.pear.player.ui.InfoPanel
import com.mediahub.pear.player.ui.OptionPill
import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.PlayerInfo
import com.mediahub.pear.player.ui.PlayerInfoRules
import com.mediahub.pear.player.ui.PlayerOverlay
import com.mediahub.pear.player.ui.PlayerPanel
import com.mediahub.pear.player.ui.PlayerPill
import com.mediahub.pear.player.ui.PlayerPillButton
import com.mediahub.pear.player.ui.SourcesPanel
import com.mediahub.pear.player.ui.SpeedPanel
import com.mediahub.pear.player.ui.desktopPillItems
import com.mediahub.pear.player.ui.isCurrentSource
import com.mediahub.pear.player.ui.isVideoMoving
import com.mediahub.pear.player.ui.pillLayout
import com.mediahub.pear.player.ui.playableSourceGroups
import com.mediahub.pear.player.ui.playerLook
import com.mediahub.pear.player.ui.playerSubtitleLine
import com.mediahub.pear.theme.PearDark
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Hosts the player: creates the engine, loads the stream and shows the video under [PlayerOverlay] (Batch 4.3a),
 * keeps the viewing history (Batch 4.3b), and runs the controls pill and its panels (Batch 4.3c): speed, the other
 * sources of the title, and the episode or movie card. A source switch keeps the position and the progress.
 * Skip intro and up next (4.3d) come later.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val data = env.data
    val prefs = data.prefs
    val engine = remember(request) { createDefaultPlayerEngine() }

    // Resume, progress and history (4.3b). The resume point is read once, before the first save can replace it.
    val resumeAt = remember(request) {
        ProgressRules.startAt(ProgressRules.resumePoint(data.history.entry(request.item.id), request.key))
    }
    // What is playing. A source switch replaces it; the title, the key and the artwork stay the same.
    var current by remember(request) { mutableStateOf(request) }
    val nowPlaying = rememberUpdatedState(current)
    // Where the next load opens: the resume point, or the playhead after a source switch.
    var loadAt by remember(request) { mutableStateOf(resumeAt) }

    val progress = remember(request, data) {
        PlaybackProgress(
            history = data.history,
            watchLog = data.watchLog,
            library = data.library,
            scrobbler = PlaybackProgress.Scrobbler { action, target, percent ->
                // Only when the profile's library is Simkl and it is connected (iOS: libraryPrefs.usesSimkl).
                if (data.libraryPrefs.usesSimkl(data.simkl.isConnected)) data.simkl.scrobble(action, target, percent)
            },
        ).also { it.begin(resumeAt) }
    }
    val snapshot = remember(engine) {
        {
            PlaybackSnapshot(
                position = engine.positionSeconds.value,
                duration = engine.durationSeconds.value,
                playing = engine.state.value == PlaybackState.Playing,
            )
        }
    }
    // Save and scrobble `stop` once. Safe to call from every way out: the close button, back, a window close, dispose.
    val leave = remember(progress, snapshot) { { progress.finish(nowPlaying.value, snapshot()) } }

    DisposableEffect(engine, leave) {
        val unregister = AppExit.register(leave) // the window's close button exits the process without disposing
        onDispose {
            unregister()
            leave() // before release: the engine's clock is still readable, and the screen may go away without onClose
            engine.release()
        }
    }
    LaunchedEffect(engine, progress) {
        engine.state.collect { s ->
            when (s) {
                PlaybackState.Playing -> progress.started(nowPlaying.value)
                // Pausing banks the resume point; so does reaching the end, so a finished episode counts at once.
                PlaybackState.Paused, PlaybackState.Ended -> progress.pausedOrBackgrounded(nowPlaying.value, snapshot())
                else -> {}
            }
        }
    }
    LaunchedEffect(engine, progress) {
        while (true) {
            delay(ProgressRules.CHECKPOINT_MILLIS)
            progress.tick(nowPlaying.value, snapshot())
        }
    }

    val window = LocalWindowControl.current
    var fullscreen by remember { mutableStateOf(false) }
    fun setFullscreen(value: Boolean) {
        fullscreen = value
        window?.isFullscreen = value
    }
    DisposableEffect(Unit) { onDispose { window?.isFullscreen = false } }

    var aspect by remember { mutableStateOf(AspectMode.Fit) }
    var performance by remember { mutableStateOf(false) }
    LaunchedEffect(engine, aspect) { engine.setAspectMode(aspect) }

    val isP2P = current.url.startsWith("p2p://")
    LaunchedEffect(engine, current.url) {
        if (!isP2P) engine.load(current.url, current.headers, startPositionSeconds = loadAt)
    }

    val state by engine.state.collectAsState()
    val engineError by engine.errorMessage.collectAsState()
    val error = if (isP2P) "Peer-to-peer sources aren't supported on desktop yet (Batch 4.5). Pick a direct or debrid source." else engineError

    val glassOn = rememberPref(prefs, PrefKeys.PLAYER_GLASS) { it.bool(PrefKeys.PLAYER_GLASS, true) }
    val blurPlaying = rememberPref(prefs, PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING) { it.bool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, false) }
    val look = playerLook(glassOn, env.glass.mode, isVideoMoving(state), blurPlaying)

    // The pill follows Settings -> Playback -> Player controls, live.
    val pinnedRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_PINNED) {
        it.string(PrefKeys.PLAYER_PILL_PINNED, PillItem.encodePinned(PillItem.defaultPinned))
    }
    val orderRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_ORDER) {
        it.string(PrefKeys.PLAYER_PILL_ORDER, PillItem.encodeOrder(PillItem.defaultOrder))
    }
    val layout = remember(pinnedRaw, orderRaw) {
        pillLayout(PillItem.decodeOrder(orderRaw), PillItem.decodePinned(pinnedRaw), desktopPillItems)
    }

    // Panels: one at a time. Sources and info load while they are open.
    var panel by remember(request) { mutableStateOf<PlayerPanel?>(null) }
    var sourceFilter by remember(request) { mutableStateOf<String?>(null) }
    var sourceGroups by remember(request) { mutableStateOf<List<Pair<Addon, List<StreamItem>>>>(emptyList()) }
    var sourcesLoading by remember(request) { mutableStateOf(false) }
    var info by remember(request) { mutableStateOf<PlayerInfo?>(null) }

    fun togglePanel(target: PlayerPanel) {
        panel = if (panel == target) null else target
    }

    // Same query the detail page runs: every add-on that serves this title or episode.
    LaunchedEffect(panel, current.key) {
        if (panel != PlayerPanel.Sources) return@LaunchedEffect
        sourceFilter = null
        val r = current
        val season = r.season
        val episode = r.episode
        val sid = if (season != null && episode != null) "${r.imdb}:$season:$episode" else r.imdb
        val addons = data.addons.enabledAddons
        val raw = ArrayList<Pair<Addon, List<StreamItem>>>()
        sourceGroups = emptyList()
        sourcesLoading = true
        try {
            data.addonClient.streamUpdates(sid, r.item.type, addons).collect { group ->
                raw.add(group)
                // Groups appear as each add-on answers, in the user's add-on order.
                sourceGroups = playableSourceGroups(AddonClient.ordered(raw, addons))
            }
        } finally {
            sourcesLoading = false
        }
    }

    // What is known at once, then TMDB / MDBList fill it in (`loadInfo` in PlayerView.swift).
    LaunchedEffect(panel, current.key) {
        if (panel != PlayerPanel.Info) return@LaunchedEffect
        val r = current
        val season = r.season
        val episode = r.episode
        info = PlayerInfoRules.basic(r)
        if (season != null && episode != null) {
            val ep = data.meta.tmdb.episodes(r.item.id, r.item.type, season).firstOrNull { it.episodeNumber == episode }
            info = PlayerInfoRules.episodeCard(r, ep)
        } else {
            val (details, mdb) = coroutineScope {
                val d = async { data.meta.tmdb.cachedDetails(r.item.id, r.item.type) }
                val m = async {
                    if (data.meta.mdblist.hasKey && r.imdb.isNotBlank()) data.meta.mdblist.ratings(r.imdb, r.item.type) else emptyList()
                }
                d.await() to m.await()
            }
            info = PlayerInfoRules.movieCard(r, details, mdb)
        }
    }

    // Same episode, different stream: the position carries over and the saved progress is kept (`selectSource`).
    fun switchSource(addon: Addon, stream: StreamItem) {
        val url = stream.url ?: return
        if (isCurrentSource(current.sourceAddonID, current.sourceSignature, addon, stream)) {
            panel = null
            return
        }
        progress.pausedOrBackgrounded(current, snapshot()) // bank the stream being left
        loadAt = engine.positionSeconds.value
        current = current.withSource(url, stream.requestHeaders, addon.id, stream.signature)
        panel = null
    }

    // The player is always dark, whatever the app appearance is (preferredColorScheme(.dark) in Swift).
    PearDark(env.theme) {
        PlayerOverlay(
            engine = engine,
            title = request.item.name,
            subtitle = playerSubtitleLine(current.season, current.episode, current.episodeTitle, current.item.releaseInfo),
            look = look,
            glass = env.glass,
            errorMessage = error,
            onClose = { leave(); onBack() },
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            modifier = modifier,
            openedAt = resumeAt.takeIf { it > 0.0 },
            panel = panel,
            onDismissPanel = { panel = null },
            onTitleClick = { panel = PlayerPanel.Info },
            pill = { onActive ->
                OptionPill(
                    engine = engine,
                    layout = layout,
                    panelOpen = panel != null,
                    speedOpen = panel == PlayerPanel.Speed,
                    sourcesOpen = panel == PlayerPanel.Sources,
                    onSpeed = { togglePanel(PlayerPanel.Speed) },
                    onSources = { togglePanel(PlayerPanel.Sources) },
                    onActive = onActive,
                )
            },
            trailing = {
                DesktopControls(
                    aspect = aspect,
                    onAspect = { aspect = AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size] },
                    performance = performance,
                    onPerformance = { performance = !performance },
                    fullscreen = fullscreen,
                    onFullscreen = { setFullscreen(!fullscreen) },
                )
            },
            panels = {
                when (panel) {
                    PlayerPanel.Speed -> SpeedPanel(engine, onClose = { panel = null })
                    PlayerPanel.Sources -> SourcesPanel(
                        groups = sourceGroups,
                        loading = sourcesLoading,
                        selectedAddonID = sourceFilter,
                        currentAddonID = current.sourceAddonID,
                        currentSignature = current.sourceSignature,
                        onSelectAddon = { sourceFilter = it },
                        onSelect = { addon, stream -> switchSource(addon, stream) },
                        onClose = { panel = null },
                    )
                    PlayerPanel.Info -> info?.let { InfoPanel(it, current.logo, onClose = { panel = null }) }
                    null -> Unit
                }
            },
            video = {
                if (!isP2P) {
                    VideoSurface(
                        engine = engine,
                        modifier = Modifier.fillMaxSize(),
                        performanceMode = performance,
                    )
                }
            },
        )
    }
}

/**
 * The desktop-only buttons after the pill: aspect, the 4K performance mode and full screen. They are not in the pill
 * and not in Settings, because the iOS player has none of them.
 */
@Composable
private fun DesktopControls(
    aspect: AspectMode,
    onAspect: () -> Unit,
    performance: Boolean,
    onPerformance: () -> Unit,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
) {
    PlayerPill {
        PlayerPillButton(label = aspect.name, onClick = onAspect)
        PlayerPillButton(
            label = if (performance) "4K: fast" else "4K: full",
            selected = performance,
            onClick = onPerformance,
        )
        PlayerPillButton(
            symbol = if (fullscreen) "arrow.down.right.and.arrow.up.left" else "arrow.up.left.and.arrow.down.right",
            contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
            onClick = onFullscreen,
        )
    }
}

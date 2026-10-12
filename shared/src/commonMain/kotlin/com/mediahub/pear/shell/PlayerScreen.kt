package com.mediahub.pear.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.meta.SkipSegment
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.EpisodeSupport
import com.mediahub.pear.player.PlaybackProgress
import com.mediahub.pear.player.PlaybackSnapshot
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.TrackKind
import com.mediahub.pear.player.TrackPicker
import com.mediahub.pear.player.ProgressRules
import com.mediahub.pear.player.VideoSurface
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.player.withSource
import com.mediahub.pear.player.ui.AudioPanel
import com.mediahub.pear.player.ui.DisplayPanel
import com.mediahub.pear.player.ui.InfoPanel
import com.mediahub.pear.player.ui.OptionPill
import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.PlayerInfo
import com.mediahub.pear.player.ui.PlayerInfoRules
import com.mediahub.pear.player.ui.PlayerOverlay
import com.mediahub.pear.player.ui.PlayerPanel
import com.mediahub.pear.player.ui.SkipButtons
import com.mediahub.pear.player.ui.SourcesPanel
import com.mediahub.pear.player.ui.SpeedPanel
import com.mediahub.pear.player.ui.SubtitlesPanel
import com.mediahub.pear.player.ui.isCurrentSource
import com.mediahub.pear.player.ui.isVideoMoving
import com.mediahub.pear.player.ui.pillItemsAvailable
import com.mediahub.pear.player.ui.pillLayout
import com.mediahub.pear.player.ui.playableSourceGroups
import com.mediahub.pear.player.ui.playerLook
import com.mediahub.pear.player.ui.playerSubtitleLine
import com.mediahub.pear.theme.PearDark
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Hosts the player: creates the engine, loads the stream and shows the video under [PlayerOverlay] (Batch 4.3a),
 * keeps the viewing history (Batch 4.3b), runs the controls pill and its panels (Batch 4.3c), and plays the next
 * episode with skip intro and autoplay (Batch 4.3d). A source switch keeps the position and the progress.
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
    val scope = rememberCoroutineScope()

    // Resume, progress and history (4.3b). The resume point is read once, before the first save can replace it.
    val resumeAt = remember(request) {
        ProgressRules.startAt(ProgressRules.resumePoint(data.history.entry(request.item.id), request.key))
    }
    // What is playing. A source switch or an episode switch replaces it; the title and the artwork stay the same.
    var current by remember(request) { mutableStateOf(request) }
    val nowPlaying = rememberUpdatedState(current)
    // Where the next load opens: the resume point, or the playhead after a source switch, or the start of a new episode.
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
    // Glass while the video plays is on by default (the Settings switch turns it off if the machine can't keep up).
    val blurPlaying = rememberPref(prefs, PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING) { it.bool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, true) }
    val look = playerLook(glassOn, env.glass.mode, isVideoMoving(state), blurPlaying)

    // The next episode, its skip timestamps, and the settings that drive them (4.3d). Looked up again for every episode
    // (`loadAux` in PlayerView.swift); a source switch keeps both.
    val episodeSupport = remember(data) {
        EpisodeSupport(data.episodes, data.meta.tmdb, data.addonClient, { data.addons.enabledAddons }, data.pinned)
    }
    var nextEp by remember(request) { mutableStateOf<Pair<Int, EpisodeItem>?>(null) }
    var segments by remember(request) { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var segmentsLoaded by remember(request) { mutableStateOf(false) }
    // The episode opened part way in (a resume): the manual skip button isn't offered for it.
    var openedMidway by remember(request) { mutableStateOf(loadAt > ProgressRules.RESUME_THRESHOLD) }
    // Changes at each episode switch, so the skip button starts fresh (its used and expired state is per episode).
    var episodeSerial by remember(request) { mutableIntStateOf(0) }
    var switching by remember(request) { mutableStateOf(false) }
    var notice by remember(request) { mutableStateOf<String?>(null) }
    val autoplay = rememberPref(prefs, PrefKeys.PLAYER_AUTOPLAY_NEXT) { it.bool(PrefKeys.PLAYER_AUTOPLAY_NEXT, true) }
    val manualSkipSeconds = rememberPref(prefs, PrefKeys.SKIP_FALLBACK_SECONDS) { it.int(PrefKeys.SKIP_FALLBACK_SECONDS, 0) }

    LaunchedEffect(current.item.id, current.key) {
        nextEp = null
        segments = emptyList()
        segmentsLoaded = false
        val req = current
        coroutineScope {
            val intro = async { data.intro.segments(req.item, req.imdb, req.season, req.episode) }
            val next = episodeSupport.next(req)
            // A show switched on under us must not be overwritten by the answers to the one before.
            currentCoroutineContext().ensureActive()
            nextEp = next
            segments = intro.await()
            segmentsLoaded = true
        }
    }

    // The pill follows Settings -> Playback -> Player controls, live. Next joins it while there is an episode after this one.
    val pinnedRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_PINNED) {
        it.string(PrefKeys.PLAYER_PILL_PINNED, PillItem.encodePinned(PillItem.defaultPinned))
    }
    val orderRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_ORDER) {
        it.string(PrefKeys.PLAYER_PILL_ORDER, PillItem.encodeOrder(PillItem.defaultOrder))
    }
    // Tracks (4.4a): the file's audio and subtitle tracks, the language preferences that choose them, and the delays.
    // A preference chosen in a menu is kept for the next episodes, as on iOS (`preferredSubtitleLanguage`).
    val audioTracks by engine.audioTracks.collectAsState()
    val subtitleTracks by engine.subtitleTracks.collectAsState()
    var preferredSubLang by remember(request) { mutableStateOf(prefs.string(PrefKeys.SUB_LANG, "off").takeIf { it != "off" }) }
    var preferredAudioLang by remember(request) { mutableStateOf(prefs.string(PrefKeys.AUDIO_LANG, "off").takeIf { it != "off" }) }
    var subDelay by remember(request) { mutableStateOf(0.0) }
    var audioDelay by remember(request) { mutableStateOf(0.0) }
    // The file whose tracks were already chosen. Each file is chosen once, when its tracks arrive (the list is empty
    // while the new file opens, because the engine clears it on start).
    var pickedFor by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.url, subtitleTracks, audioTracks) {
        if (pickedFor == current.url || (subtitleTracks.isEmpty() && audioTracks.isEmpty())) return@LaunchedEffect
        preferredAudioLang?.let { lang ->
            TrackPicker.bestAudio(audioTracks, lang)?.takeIf { !it.isSelected }?.let { engine.selectTrack(TrackKind.Audio, it.id) }
        }
        preferredSubLang?.let { lang ->
            TrackPicker.bestSubtitle(subtitleTracks, lang)?.takeIf { !it.isSelected }?.let { engine.selectTrack(TrackKind.Subtitle, it.id) }
        }
        pickedFor = current.url
    }
    // The delays are set again whenever a file starts playing: a new file may start them from zero.
    LaunchedEffect(state, current.url) {
        if (state == PlaybackState.Playing) {
            engine.setAudioDelay(audioDelay)
            engine.setSubtitleDelay(subDelay)
        }
    }

    val layout = remember(pinnedRaw, orderRaw, nextEp, subtitleTracks.isNotEmpty(), audioTracks.size) {
        pillLayout(
            PillItem.decodeOrder(orderRaw),
            PillItem.decodePinned(pinnedRaw),
            pillItemsAvailable(nextEp != null, hasSubtitles = subtitleTracks.isNotEmpty(), hasAudio = audioTracks.size > 1),
        )
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

    fun flash(message: String) {
        notice = message
        scope.launch {
            delay(2_600)
            if (notice == message) notice = null
        }
    }

    // Jumps to an episode (`switchEpisode` in PlayerView.swift): finds its stream first, so a failure leaves the current
    // episode playing; then banks the episode being left and loads the new one at its own resume point.
    suspend fun switchEpisode(season: Int, episode: EpisodeItem) {
        if (switching) return
        switching = true
        try {
            val next = episodeSupport.resolve(current, season, episode)
            if (next == null) {
                flash("No playable source found for that episode")
                return
            }
            progress.finish(current, snapshot()) // save and scrobble the episode we are leaving
            val at = ProgressRules.startAt(ProgressRules.resumePoint(data.history.entry(next.item.id), next.key))
            loadAt = at
            openedMidway = at > ProgressRules.RESUME_THRESHOLD
            current = next
            episodeSerial++
            progress.begin(at) // the new episode starts its own session: its own start, resume guard and watch flags
            panel = null
        } finally {
            switching = false
        }
    }

    // The next episode, from the pill, the skip button, or on its own when the file ends (autoplay).
    fun playNext() {
        val (season, episode) = nextEp ?: return
        scope.launch { switchEpisode(season, episode) }
    }

    LaunchedEffect(state) {
        if (state == PlaybackState.Ended && autoplay) playNext()
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
            openedAt = loadAt.takeIf { it > 0.0 },
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
                    displayOpen = panel == PlayerPanel.Display,
                    onSpeed = { togglePanel(PlayerPanel.Speed) },
                    onSources = { togglePanel(PlayerPanel.Sources) },
                    onDisplay = { togglePanel(PlayerPanel.Display) },
                    subtitlesOpen = panel == PlayerPanel.Subtitles,
                    subtitlesOn = subtitleTracks.any { it.isSelected },
                    onSubtitles = { togglePanel(PlayerPanel.Subtitles) },
                    audioOpen = panel == PlayerPanel.Audio,
                    onAudio = { togglePanel(PlayerPanel.Audio) },
                    onNext = { playNext() },
                    nextEnabled = !switching,
                    fullscreen = fullscreen,
                    onFullscreen = { setFullscreen(!fullscreen) },
                    onActive = onActive,
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
                    PlayerPanel.Display -> DisplayPanel(
                        aspect = aspect,
                        onAspect = { aspect = it },
                        fastFourK = performance,
                        onFastFourK = { performance = it },
                        onClose = { panel = null },
                    )
                    PlayerPanel.Subtitles -> SubtitlesPanel(
                        tracks = subtitleTracks,
                        onPick = { t ->
                            preferredSubLang = t?.let { it.language?.takeIf { l -> l.isNotBlank() } ?: it.title }
                            engine.selectTrack(TrackKind.Subtitle, t?.id)
                        },
                        delaySeconds = subDelay,
                        onDelay = {
                            subDelay = it
                            engine.setSubtitleDelay(it)
                        },
                        onClose = { panel = null },
                    )
                    PlayerPanel.Audio -> AudioPanel(
                        tracks = audioTracks,
                        onPick = { t ->
                            preferredAudioLang = t.language?.takeIf { l -> l.isNotBlank() } ?: t.title
                            engine.selectTrack(TrackKind.Audio, t.id)
                        },
                        delaySeconds = audioDelay,
                        onDelay = {
                            audioDelay = it
                            engine.setAudioDelay(it)
                        },
                        onClose = { panel = null },
                    )
                    null -> Unit
                }
            },
            skip = { shown ->
                key(episodeSerial) {
                    SkipButtons(
                        engine = engine,
                        controlsShown = shown,
                        segments = segments,
                        loaded = segmentsLoaded,
                        hasNext = nextEp != null,
                        manualSeconds = if (shown && current.season != null && manualSkipSeconds > 0) manualSkipSeconds.toDouble() else null,
                        openedMidway = openedMidway,
                        onSeek = { engine.seek(it) },
                        onNext = { playNext() },
                    )
                }
            },
            notice = notice,
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

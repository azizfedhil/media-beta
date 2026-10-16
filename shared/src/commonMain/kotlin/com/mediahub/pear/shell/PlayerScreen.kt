package com.mediahub.pear.shell

import kotlinx.coroutines.CancellationException
import com.mediahub.pear.p2p.p2pStatusLine
import com.mediahub.pear.p2p.p2pSource
import com.mediahub.pear.p2p.P2PFailure
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
import com.mediahub.pear.addons.OpenSubtitlesClient
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.PowerStatus
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.meta.SkipSegment
import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.EpisodeSupport
import com.mediahub.pear.player.PlaybackProgress
import com.mediahub.pear.player.PlaybackSnapshot
import com.mediahub.pear.player.KeepAwakeRules
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.createKeepAwake
import com.mediahub.pear.player.media.MediaCommand
import com.mediahub.pear.player.media.MediaEffect
import com.mediahub.pear.player.media.MediaMetadata
import com.mediahub.pear.player.media.MediaSessionRules
import com.mediahub.pear.player.media.createSystemMediaSession
import com.mediahub.pear.player.TrackKind
import com.mediahub.pear.player.TrackPicker
import com.mediahub.pear.player.ProgressRules
import com.mediahub.pear.player.PipState
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
import com.mediahub.pear.player.ui.SubtitleLayer
import com.mediahub.pear.player.SubtitleStyle
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import com.mediahub.pear.theme.PearTransitions

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
    // Leaving the stream ends it, and the engine with it: the engine runs only for a stream the user is watching.
    val leave = remember(progress, snapshot) {
        {
            println("PEAR-CW leave snapshot=${snapshot()}") // TEMP-CW
            progress.finish(nowPlaying.value, snapshot())
            data.p2p.engine.requestStop()
        }
    }

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
    // Leaving the app pauses the film (Android has no background playback, decisions D6). AppLifecycle fires only when
    // no Picture in Picture window is open, so this is "pause unless PiP".
    LaunchedEffect(engine, progress) {
        AppLifecycle.backgrounded.collect {
            // Bank the position before the pause, with a full save, so the Continue Watching row is current (T8-b.3).
            progress.bank(nowPlaying.value, snapshot())
            // The activity flushes its settings when it stops, which can run before this collector. Flush again here so the
            // banked position is on disk, not only in memory.
            data.storage.flush()
            engine.pause()
            // iOS ends a stream that is not playing when the app goes to the background. The film is paused just above.
            data.p2p.engine.requestStop()
        }
    }
    LaunchedEffect(engine, progress) {
        while (true) {
            delay(ProgressRules.CHECKPOINT_MILLIS)
            progress.tick(nowPlaying.value, snapshot())
        }
    }

    // The window as the player found it: maximized or a plain window. Leaving the player puts that back, so the app doesn't
    // drop out of a maximized state after a film. Full screen inside the player toggles from there, and turning it off
    // returns to the same state. Full screen is never a state to return to: it belongs to the player, and is not saved.
    val window = LocalWindowControl.current
    val windowBefore = remember { window?.mode ?: WindowMode.Windowed }
    val windowAfterFullscreen = if (windowBefore == WindowMode.Fullscreen) WindowMode.Maximized else windowBefore
    // Read from the window itself, not kept as a copy: the button, the icon and Escape always agree with what the window is.
    val fullscreen = window?.mode == WindowMode.Fullscreen
    fun setFullscreen(value: Boolean) {
        window?.mode = if (value) WindowMode.Fullscreen else windowAfterFullscreen
    }
    DisposableEffect(Unit) { onDispose { window?.mode = windowAfterFullscreen } }
    // Escape leaves full screen before it leaves the player: the shell asks this first (see handleShellKey).
    val exitFullscreen: () -> Boolean = { setFullscreen(false); true }
    LaunchedEffect(fullscreen) { env.shell.escapeHook = if (fullscreen) exitFullscreen else null }
    DisposableEffect(Unit) { onDispose { env.shell.escapeHook = null } }
    // A phone opens the player full screen, as the iOS player is landscape. Leaving restores the window (above).
    LaunchedEffect(Unit) { if (PlatformCaps.playerStartsFullscreen) setFullscreen(true) }

    var aspect by remember { mutableStateOf(AspectMode.Fit) }
    var performance by remember { mutableStateOf(false) }
    LaunchedEffect(engine, aspect) { engine.setAspectMode(aspect) }

    // A torrent opens through the P2P engine first: the engine starts if it isn't running and resolves the file, then mpv
    // reads the loopback URL it returns. A link ends any torrent still streaming; the engine stays loaded and idle.
    var p2pOpening by remember(request) { mutableStateOf(false) }
    var p2pError by remember(request) { mutableStateOf<String?>(null) }
    var p2pStatus by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(engine, current.url) {
        val torrent = current.p2p
        p2pError = null
        if (torrent == null) {
            // Leaving a torrent for a link: the user has left that stream, so the engine goes with it (4.5d).
            data.p2p.engine.requestStop()
            engine.load(current.url, current.headers, startPositionSeconds = loadAt)
            return@LaunchedEffect
        }
        p2pOpening = true
        try {
            if (!data.p2p.enabled.value) throw P2PFailure.Disabled
            if (!data.p2p.canStreamNow()) throw P2PFailure.NetworkBlocked
            val stream = data.p2p.engine.open(torrent)
            p2pOpening = false
            engine.load(stream.url, emptyMap(), startPositionSeconds = loadAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            p2pOpening = false
            p2pError = e.message ?: "Peer-to-peer playback failed."
        }
    }
    // Leaving the network the stream was allowed on ends it at once (iOS: Wi-Fi only, no cellular P2P traffic). Desktop's
    // network never changes, so this never fires there.
    LaunchedEffect(data.p2p) {
        data.p2p.networkAllowed.collect { allowed ->
            if (!allowed && current.p2p != null) data.p2p.engine.requestStop()
        }
    }
    // The status line for a torrent (peers, speed, progress), read once a second. Nothing for a link.
    LaunchedEffect(current.url) {
        while (true) {
            p2pStatus = if (current.p2p != null) p2pStatusLine(data.p2p.engine.status().stats) else null
            delay(1_000)
        }
    }

    val state by engine.state.collectAsState()
    val engineError by engine.errorMessage.collectAsState()
    val error = p2pError ?: engineError

    val glassOn = rememberPref(prefs, PrefKeys.PLAYER_GLASS) { it.bool(PrefKeys.PLAYER_GLASS, true) }
    // Glass while the video plays is on by default (the Settings switch turns it off if the machine can't keep up).
    val blurPlaying = rememberPref(prefs, PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING) { it.bool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, true) }
    // Android only: the Liquid Glass switch in Settings, Playback. Off means Frosted.
    val liquid = rememberPref(prefs, PrefKeys.ANDROID_PLAYER_LIQUID_GLASS) { it.bool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false) }
    // Decided once when the player opens: swapping the surface type during a film would flash. Battery saver means Frosted.
    val sampleable = remember {
        if (PlatformCaps.hasPlayerGlassChoice) liquid && PlatformCaps.videoBlurSupported && !PowerStatus.lowBattery
        else PlatformCaps.videoBlurSupported
    }
    // Where the picture cannot be sampled, glass cannot blur it: the controls get the Frosted look.
    val look = playerLook(
        glassOn,
        env.glass.mode,
        isVideoMoving(state),
        blurWhilePlaying = if (PlatformCaps.hasPlayerGlassChoice) true else blurPlaying && PlatformCaps.videoBlurSupported,
        videoSampleable = sampleable,
    )
    // Picture in Picture follows the Settings switch. The platform opens the window; the player says when it may.
    val pipAllowed = rememberPref(prefs, PrefKeys.PLAYER_PIP) { it.bool(PrefKeys.PLAYER_PIP, true) }
    LaunchedEffect(state, pipAllowed) { PipState.wanted = pipAllowed && state == PlaybackState.Playing }
    DisposableEffect(Unit) { onDispose { PipState.wanted = false } }
    // The viewer's subtitle look (Settings -> Subtitles -> Appearance), followed live. It draws the plain-text subtitles.
    val subStyle = rememberPref(prefs, PrefKeys.SUB_STYLE) { SubtitleStyle.decode(it.string(PrefKeys.SUB_STYLE, "")) }

    // The next episode, its skip timestamps, and the settings that drive them (4.3d). Looked up again for every episode
    // (`loadAux` in PlayerView.swift); a source switch keeps both.
    val episodeSupport = remember(data) {
        EpisodeSupport(data.episodes, data.meta.tmdb, data.addonClient, { data.addons.enabledAddons }, data.pinned, { data.p2p.canStreamNow() })
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

    // Online subtitles (4.4b): OpenSubtitles and the add-ons that offer them, looked up once per episode. A file added
    // from the list is a track of the file playing, so the map of added files starts again with each file.
    val lookupKey = "${current.imdb}:${current.key}"
    var onlineSubs by remember(request) { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var onlineLoading by remember(request) { mutableStateOf(false) }
    var onlineFor by remember(request) { mutableStateOf<String?>(null) }
    var addedOnline by remember(current.url) { mutableStateOf(emptyMap<String, Int>()) }
    var autoOnlineFor by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(lookupKey) {
        if (onlineFor == lookupKey) return@LaunchedEffect
        onlineSubs = emptyList()
        onlineLoading = true
        val r = current
        val found = data.subtitleSources.search(r.imdb, r.season, r.episode)
        onlineSubs = found
        onlineLoading = false
        onlineFor = lookupKey
    }

    // The subtitle button shows whenever online lookup is on, as on iOS (`pillHas` for subtitles).
    val hasSubtitleChoice = subtitleTracks.isNotEmpty() || data.subtitles.isEnabled
    val layout = remember(pinnedRaw, orderRaw, nextEp, hasSubtitleChoice, audioTracks.size, pipAllowed) {
        pillLayout(
            PillItem.decodeOrder(orderRaw),
            PillItem.decodePinned(pinnedRaw),
            pillItemsAvailable(nextEp != null, hasSubtitles = hasSubtitleChoice, hasAudio = audioTracks.size > 1)
                .let { if (pipAllowed) it else it - PillItem.Pip },
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
                sourceGroups = playableSourceGroups(AddonClient.ordered(raw, addons), p2pReady = data.p2p.canStreamNow())
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
        val url = stream.url ?: stream.p2pSource()?.let { "p2p://${it.infoHash}" } ?: return
        if (isCurrentSource(current.sourceAddonID, current.sourceSignature, addon, stream)) {
            panel = null
            return
        }
        progress.pausedOrBackgrounded(current, snapshot()) // bank the stream being left
        loadAt = engine.positionSeconds.value
        current = current.withSource(url, stream.requestHeaders, addon.id, stream.signature, stream.p2pSource())
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

    // System media controls (Batch 4.6b): the media keys, inside the player and as global keys where the platform has
    // them, and what the OS shows for this title. Every command runs here, so Next is the pill's Next, not a second path.
    fun runMedia(command: MediaCommand) {
        when (MediaSessionRules.effect(command, nextAvailable = nextEp != null)) {
            MediaEffect.TogglePlay -> engine.togglePlay()
            MediaEffect.Play -> engine.play()
            MediaEffect.Pause -> engine.pause()
            MediaEffect.PlayNext -> playNext()
            MediaEffect.RestartTitle -> engine.seek(0.0)
            MediaEffect.None -> Unit
        }
    }
    // Created with the player and released with it, so the keys are only ours while a player is on screen.
    val mediaSession = remember(engine) { createSystemMediaSession() }
    DisposableEffect(mediaSession) { onDispose { mediaSession.release() } }
    LaunchedEffect(mediaSession) { mediaSession.commands.collect { runMedia(it) } }
    LaunchedEffect(mediaSession, state, current, nextEp) {
        mediaSession.update(
            status = MediaSessionRules.status(state),
            metadata = MediaMetadata(
                title = current.item.name,
                subtitle = playerSubtitleLine(current.season, current.episode, current.episodeTitle, current.item.releaseInfo),
                artworkUrl = current.thumb ?: current.item.background ?: current.item.poster,
                durationSeconds = engine.durationSeconds.value,
            ),
            canGoNext = nextEp != null,
        )
    }

    // The screen stays awake while the stream opens or plays, and not while paused or after a failure (Batch 4.6c; iOS: the
    // idle timer). Created with the player and released with it, so the system's timers come back when the player closes.
    val keepAwake = remember(engine) { createKeepAwake() }
    DisposableEffect(keepAwake) { onDispose { keepAwake.release() } }
    LaunchedEffect(keepAwake, state, error) { keepAwake.setAwake(KeepAwakeRules.wanted(state, error)) }

    // Adds an online file to this file and plays it (`useOnline` in PlayerView.swift). The engine reads it into the track
    // list; the new track is the one whose title is the source name and that wasn't there before.
    fun addOnline(s: OnlineSubtitle) {
        scope.launch {
            addedOnline[s.id]?.let { track ->
                engine.selectTrack(TrackKind.Subtitle, track)
                preferredSubLang = s.lang
                return@launch
            }
            val before = engine.subtitleTracks.value.map { it.id }.toSet()
            engine.addExternalSubtitle(s.url, s.source, s.lang)
            val added = withTimeoutOrNull(ONLINE_LOAD_MILLIS) {
                engine.subtitleTracks.first { list -> list.any { it.id !in before && it.title == s.source } }
            }?.lastOrNull { it.id !in before && it.title == s.source }
            if (added == null) {
                flash("Couldn't load that subtitle")
                return@launch
            }
            addedOnline = addedOnline + (s.id to added.id)
            engine.selectTrack(TrackKind.Subtitle, added.id)
            preferredSubLang = s.lang
        }
    }

    // Once per file, when it is playing or has its tracks: the first online file in the preferred language, if the file
    // has nothing in that language and no subtitle is on already (`loadOnlineSubtitles` with auto).
    LaunchedEffect(lookupKey, onlineFor, subtitleTracks, state, current.url) {
        val ready = onlineFor == lookupKey && (state == PlaybackState.Playing || subtitleTracks.isNotEmpty())
        if (!ready || autoOnlineFor == current.url) return@LaunchedEffect
        autoOnlineFor = current.url
        val pick = TrackPicker.autoOnline(onlineSubs, preferredSubLang, subtitleTracks, subtitleTracks.any { it.isSelected })
        if (pick != null) addOnline(pick)
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
            onMediaCommand = { runMedia(it) },
            p2pOpening = p2pOpening,
            p2pStatus = p2pStatus,
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
                // The open panel fades and rises in and out; a switch from one panel to another crossfades.
                AnimatedContent(
                    targetState = panel,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { PearTransitions.panelIn togetherWith PearTransitions.panelOut },
                    label = "player-panel",
                ) { openPanel ->
                    when (openPanel) {
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
                            online = OpenSubtitlesClient.choices(onlineSubs, preferredSubLang).filter { it.id !in addedOnline },
                            loadingOnline = onlineLoading,
                            onPickOnline = { addOnline(it) },
                            style = subStyle,
                            onStyle = { prefs.setString(PrefKeys.SUB_STYLE, it.encoded()) },
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
            subtitles = { shown ->
                // Read here, not in the screen, so a cue changing redraws only the subtitle line.
                val cue by engine.subtitleText.collectAsState()
                SubtitleLayer(cue, subStyle, shown)
            },
            notice = notice,
            video = {
                VideoSurface(
                    engine = engine,
                    modifier = Modifier.fillMaxSize(),
                    performanceMode = performance,
                    sampleable = sampleable,
                )
            },
        )
    }
}

/** How long a file has to show up in the track list after it is added (online subtitles). */
private const val ONLINE_LOAD_MILLIS = 10_000L

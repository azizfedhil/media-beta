package com.mediahub.pear.shell

import kotlinx.coroutines.CancellationException
import com.mediahub.pear.p2p.FINDING_PEERS_PHRASE_SECONDS
import com.mediahub.pear.p2p.FindingPeersPhrases
import com.mediahub.pear.p2p.isLookingForPeers
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
import androidx.compose.ui.unit.dp
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
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier
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
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.log.PearTrace
import com.mediahub.pear.log.TraceRecompose
import com.mediahub.pear.log.safeLogUrl
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
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.party.PARTY_GUEST_NOTICE
import com.mediahub.pear.party.PartyPlayback
import com.mediahub.pear.party.PartyPick
import com.mediahub.pear.party.PartyRole
import com.mediahub.pear.party.PartySource
import com.mediahub.pear.party.PartySourcePicker
import com.mediahub.pear.party.roomPositionNow
import com.mediahub.pear.party.toPartySource
import com.mediahub.pear.player.PlayerEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
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
    TraceRecompose("PlayerScreen")
    val env = LocalAppEnv.current
    val data = env.data
    val prefs = data.prefs
    val engine = remember(request) { createDefaultPlayerEngine().also { PearLog.i("Player", "engine created") } }
    val scope = rememberCoroutineScope()
    // The watch party (batch K5). While this device is in a room, the host's controls go to the room, and a guest's controls
    // are requests to the host. The controls below drive `controls`; the engine stays raw for the room's adapters and for the
    // lifecycle, which must not wait on a room.
    val party = data.party
    val partyRole by party.role.collectAsState()
    var partyNote by remember(request) { mutableStateOf<String?>(null) }
    // A guest's line about the room's source: the host is choosing, or no source is on this device (batch K6).
    var roomNote by remember(request) { mutableStateOf<String?>(null) }
    val playback = remember(engine) {
        PartyPlayback(engine, party, drivenByRoom = request.partyRoom, scope = scope) { message ->
            partyNote = message
            scope.launch {
                delay(2_600)
                if (partyNote == message) partyNote = null
            }
        }
        .also { it.openedFor = request.partyFor }
    }
    val controls: PlayerEngine = playback.controls
    // A guest whose player the room drives: no seeking, speed, next episode or skipping. The host decides those.
    val guestHere = partyRole == PartyRole.Guest && request.partyRoom
    // The guest's reminder that the host drives playback: shown for a few seconds on arrival, then it goes away.
    var guestHint by remember(request) { mutableStateOf(false) }
    LaunchedEffect(guestHere, request) {
        if (guestHere) {
            guestHint = true
            delay(4_000)
            guestHint = false
        } else {
            guestHint = false
        }
    }
    DisposableEffect(playback) {
        playback.start()
        onDispose { playback.stop() }
    }

    // Resume, progress and history (4.3b). The resume point is read once, before the first save can replace it.
    val resumeAt = remember(request) {
        ProgressRules.startAt(ProgressRules.resumePoint(data.history.entry(request.item.id), request.key))
    }
    // What is playing. A source switch or an episode switch replaces it; the title and the artwork stay the same.
    var current by remember(request) { mutableStateOf(request) }
    val nowPlaying = rememberUpdatedState(current)
    // Where the next load opens: the resume point, or the playhead after a source switch, or the start of a new episode.
    var loadAt by remember(request) { mutableStateOf(if (request.partyRoom) party.roomPositionNow() ?: resumeAt else resumeAt) }
    // The room learns what this player plays: the title, the episode and the source it opened on (never a URL).
    val partySource = remember(current) { current.toPartySource() }
    LaunchedEffect(playback, partySource) { playback.setSource(partySource, loadAt) }

    val progress = remember(request, data) {
        PlaybackProgress(
            history = data.history,
            watchLog = data.watchLog,
            library = data.library,
            scrobbler = PlaybackProgress.Scrobbler { action, target, percent ->
                // Only when the profile's library is Simkl and it is connected (iOS: libraryPrefs.usesSimkl).
                if (data.libraryPrefs.usesSimkl(data.simkl.isConnected)) data.simkl.scrobble(action, target, percent)
            },
            saveSink = data.pairSync,
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
            progress.finish(nowPlaying.value, snapshot())
            playback.stop()
            data.p2p.engine.requestStop()
        }
    }

    DisposableEffect(engine, leave) {
        PearTrace.event("UI") { "route player opened" }
        val unregister = AppExit.register(leave) // the window's close button exits the process without disposing
        onDispose {
            PearTrace.event("UI") { "route player disposed" }
            unregister()
            leave() // before release: the engine's clock is still readable, and the screen may go away without onClose
            PearLog.i("Player", "engine released")
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
    // An app that was already full screen (F11) when the player opened stays that way after it.
    DisposableEffect(Unit) { onDispose { if (windowBefore != WindowMode.Fullscreen) window?.mode = windowAfterFullscreen } }
    // Escape leaves full screen before it leaves the player: the shell asks this first (see handleShellKey).
    val exitFullscreen: () -> Boolean = { setFullscreen(false); true }
    LaunchedEffect(fullscreen) { env.shell.escapeHook = if (fullscreen) exitFullscreen else null }
    DisposableEffect(Unit) { onDispose { env.shell.escapeHook = null } }
    // A phone opens the player full screen, as the iOS player is landscape. Leaving restores the window (above).
    LaunchedEffect(Unit) { if (PlatformCaps.playerStartsFullscreen) setFullscreen(true) }

    var aspect by remember { mutableStateOf(AspectMode.Fit) }
    // Fast 4K starts on for a Low phone, which has the least GPU to spare. Nothing is saved for it, so this is the default each time.
    var performance by remember { mutableStateOf(DeviceProfile.tier == DeviceTier.Low) }
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
            PearTrace.event("Player") { "load ${safeLogUrl(current.url)}" }
            engine.load(current.url, current.headers, startPositionSeconds = loadAt)
            playback.sourceLoaded()
            return@LaunchedEffect
        }
        p2pOpening = true
        try {
            if (!data.p2p.enabled.value) throw P2PFailure.Disabled
            if (!data.p2p.canStreamNow()) throw P2PFailure.NetworkBlocked
            val stream = data.p2p.engine.open(torrent)
            p2pOpening = false
            PearTrace.event("Player") { "load ${safeLogUrl(stream.url)} (torrent)" }
            engine.load(stream.url, emptyMap(), startPositionSeconds = loadAt)
            playback.sourceLoaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            p2pOpening = false
            p2pError = e.message ?: "Peer-to-peer playback failed."
            PearLog.w("P2P", "playback failed: $p2pError")
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
        // Android: a link has no status line, so its loop is not started at all. Desktop keeps the loop as it is.
        if (PlatformCaps.isTouchFirst && current.p2p == null) {
            p2pStatus = null
            return@LaunchedEffect
        }
        // While no peer is found, the line is a phrase that changes every few seconds; once there are peers, the numbers.
        val phrases = FindingPeersPhrases()
        var phrase = ""
        var secondsSearching = 0
        while (true) {
            if (current.p2p != null) {
                val stats = data.p2p.engine.status().stats
                if (isLookingForPeers(stats)) {
                    if (secondsSearching % FINDING_PEERS_PHRASE_SECONDS == 0) phrase = phrases.next()
                    p2pStatus = phrase
                    secondsSearching++
                } else {
                    p2pStatus = p2pStatusLine(stats)
                    secondsSearching = 0
                    phrases.restart()
                }
            } else {
                p2pStatus = null
            }
            delay(1_000)
        }
    }

    val state by engine.state.collectAsState()
    val engineError by engine.errorMessage.collectAsState()
    LaunchedEffect(engineError) { engineError?.let { PearLog.w("Player", "load error: $it") } }
    val error = p2pError ?: engineError

    val glassOn = rememberPref(prefs, PrefKeys.PLAYER_GLASS) { it.bool(PrefKeys.PLAYER_GLASS, true) }
    // Glass while the video plays is on by default (the Settings switch turns it off if the machine can't keep up).
    val blurPlaying = rememberPref(prefs, PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING) { it.bool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, true) }
    // Android only: the Liquid Glass switch in Settings, Playback. Off means Frosted.
    val liquid = rememberPref(prefs, PrefKeys.ANDROID_PLAYER_LIQUID_GLASS) { it.bool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false) }
    // Decided once when the player opens: swapping the surface type during a film would flash. Battery saver means Frosted.
    val sampleable = remember {
        if (PlatformCaps.hasPlayerGlassChoice) liquid && PlatformCaps.videoBlurSupported && !PowerStatus.saving
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
    // Each change of the look is logged once, and the settings the player opened with are logged once.
    LaunchedEffect(look) {
        PearTrace.event("UI") { "look -> $look" }
        PearLog.context("player.look", look.name)
    }
    LaunchedEffect(Unit) {
        PearTrace.event("UI") {
            "player opened glassOn=$glassOn blurPlaying=$blurPlaying glassMode=${env.glass.mode} sampleable=$sampleable performance=$performance"
        }
    }
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
    val layout = remember(pinnedRaw, orderRaw, nextEp, hasSubtitleChoice, audioTracks.size, pipAllowed, guestHere) {
        pillLayout(
            PillItem.decodeOrder(orderRaw),
            PillItem.decodePinned(pinnedRaw),
            pillItemsAvailable(nextEp != null && !guestHere, hasSubtitles = hasSubtitleChoice, hasAudio = audioTracks.size > 1)
                .let { if (pipAllowed) it else it - PillItem.Pip }
                .let { if (guestHere) it - PillItem.Speed else it },
        )
    }

    // Panels: one at a time. Sources and info load while they are open.
    var panel by remember(request) { mutableStateOf<PlayerPanel?>(null) }
    var sourceFilter by remember(request) { mutableStateOf<String?>(null) }
    var sourceGroups by remember(request) { mutableStateOf<List<Pair<Addon, List<StreamItem>>>>(emptyList()) }
    var sourcesLoading by remember(request) { mutableStateOf(false) }
    var info by remember(request) { mutableStateOf<PlayerInfo?>(null) }
    // A card that has been loaded is kept, so opening it again shows it at once and does not ask TMDB again.
    val infoCache = remember { HashMap<String, PlayerInfo>() }
    fun infoKey(r: PlayRequest) = "${r.item.id}|${r.key}"

    fun togglePanel(target: PlayerPanel) {
        panel = if (panel == target) null else target
    }

    // The card exists before the panel opens, so it animates in with content.
    fun openInfo() {
        info = infoCache[infoKey(current)] ?: PlayerInfoRules.basic(current)
        panel = PlayerPanel.Info
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
        val cacheKey = infoKey(r)
        infoCache[cacheKey]?.let {
            info = it
            return@LaunchedEffect
        }
        info = PlayerInfoRules.basic(r)
        val season = r.season
        val episode = r.episode
        if (season != null && episode != null) {
            val ep = data.meta.tmdb.episodes(r.item.id, r.item.type, season).firstOrNull { it.episodeNumber == episode }
            val card = PlayerInfoRules.episodeCard(r, ep)
            info = card
            if (ep != null) infoCache[cacheKey] = card
        } else {
            val (details, mdb) = coroutineScope {
                val d = async { data.meta.tmdb.cachedDetails(r.item.id, r.item.type) }
                val m = async {
                    if (data.meta.mdblist.hasKey && r.imdb.isNotBlank()) data.meta.mdblist.ratings(r.imdb, r.item.type) else emptyList()
                }
                d.await() to m.await()
            }
            val card = PlayerInfoRules.movieCard(r, details, mdb)
            info = card
            if (details != null) infoCache[cacheKey] = card
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
        if (request.partyRoom) roomNote = null
        panel = null
    }

    fun flash(message: String) {
        notice = message
        scope.launch {
            delay(2_600)
            if (notice == message) notice = null
        }
    }

    // Watch party, guest side (batch K6). The room's source is found on this device: the same stream the host used, else the
    // same torrent, else the usual pick. The room sends the source only, never a URL.
    suspend fun applyRoomSource(src: PartySource?) {
        if (src == null) {
            roomNote = "Host is choosing a title..."
            return
        }
        if (src == playback.openedFor) {
            // Opened for this source already, or resolved it: just follow the room again.
            playback.sourceLoaded()
            return
        }
        roomNote = "Finding the host's source on your device..."
        val pick = try {
            PartySourcePicker.pick(
                source = src,
                addonClient = data.addonClient,
                addons = data.addons.enabledAddons,
                pin = data.pinned.pin(src.imdb),
                p2pOff = PartySourcePicker.p2pOffReason(data.p2p.enabled.value, data.p2p.canStreamNow()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PearLog.w("Party", "looking for the room's source failed: ${e::class.simpleName}")
            PartyPick.Missing("Something went wrong looking for it.")
        }
        when (pick) {
            is PartyPick.Found -> {
                playback.openedFor = src
                roomNote = null
                flash(pick.note)
                loadAt = party.roomPositionNow() ?: 0.0
                if (pick.request.url == current.url) {
                    playback.sourceLoaded()
                } else {
                    current = pick.request
                }
            }
            is PartyPick.Missing -> {
                PearLog.i("Party", "the room's source is not on this device: ${pick.reason}")
                // Nothing to play here: the player stops, the room sees this guest as not ready, and the line says what to do.
                roomNote = "No source on your device. ${pick.reason} Use Sources to pick one."
                engine.pause()
                playback.sourceUnavailable()
            }
        }
    }

    LaunchedEffect(playback) {
        playback.roomSourceChanges.collect { event ->
            if (request.partyRoom && event.seq > 0L) applyRoomSource(event.source)
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
        when (MediaSessionRules.effect(command, nextAvailable = nextEp != null && !guestHere)) {
            MediaEffect.TogglePlay -> controls.togglePlay()
            MediaEffect.Play -> controls.play()
            MediaEffect.Pause -> controls.pause()
            MediaEffect.PlayNext -> playNext()
            MediaEffect.RestartTitle -> controls.seek(0.0)
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
            canGoNext = nextEp != null && !guestHere,
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

    LaunchedEffect(state, guestHere) {
        // A guest's player does not start the next episode itself: the room's host decides what plays.
        if (state == PlaybackState.Ended && autoplay && !guestHere) playNext()
    }

    // The player is always dark, whatever the app appearance is (preferredColorScheme(.dark) in Swift).
    PearDark(env.theme) {
        PlayerOverlay(
            engine = controls,
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
            remote = guestHere,
            trailing = {
                if (partyRole != PartyRole.None) {
                    GlassButton(onClick = { env.shell.openParty() }, symbol = "person.fill", size = 40.dp)
                }
            },
            panel = panel,
            onDismissPanel = { panel = null },
            onTitleClick = { openInfo() },
            pill = { onActive ->
                OptionPill(
                    engine = controls,
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
                    transitionSpec = { PearTransitions.panelIn togetherWith PearTransitions.panelOut using SizeTransform(clip = false) { _, _ -> snap() } },
                    label = "player-panel",
                ) { openPanel ->
                    when (openPanel) {
                        PlayerPanel.Speed -> SpeedPanel(controls, onClose = { panel = null })
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
                        PlayerPanel.Info -> Unit
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
                // Outside the AnimatedContent on purpose: it has its own enter and exit, and must be in the tree before it is shown.
                InfoPanel(
                    visible = panel == PlayerPanel.Info,
                    info = info,
                    logo = current.logo,
                    onClose = { panel = null },
                )
            },
            skip = { shown ->
                // A guest's skip would move the picture alone, so a guest is not offered it: the room's host decides.
                if (!guestHere) key(episodeSerial) {
                    SkipButtons(
                        engine = controls,
                        controlsShown = shown,
                        segments = segments,
                        loaded = segmentsLoaded,
                        hasNext = nextEp != null,
                        manualSeconds = if (shown && current.season != null && manualSkipSeconds > 0) manualSkipSeconds.toDouble() else null,
                        openedMidway = openedMidway,
                        onSeek = { controls.seek(it) },
                        onNext = { playNext() },
                    )
                }
            },
            subtitles = { shown ->
                // Read here, not in the screen, so a cue changing redraws only the subtitle line.
                val cue by engine.subtitleText.collectAsState()
                SubtitleLayer(cue, subStyle, shown)
            },
            notice = notice ?: partyNote ?: roomNote ?: if (guestHere && guestHint) PARTY_GUEST_NOTICE else null,
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

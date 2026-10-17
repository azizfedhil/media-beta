package com.mediahub.pear.data

import com.mediahub.pear.p2p.P2PService
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.AddonStore
import com.mediahub.pear.addons.OpenSubtitlesClient
import com.mediahub.pear.addons.PinnedSources
import com.mediahub.pear.addons.SubtitleSources
import com.mediahub.pear.history.EpisodeLoader
import com.mediahub.pear.history.LibraryPrefs
import com.mediahub.pear.history.LocalLibrary
import com.mediahub.pear.history.WatchHistory
import com.mediahub.pear.history.WatchLog
import com.mediahub.pear.meta.IntroClient
import com.mediahub.pear.meta.MetaServices
import com.mediahub.pear.meta.TitleClassifier
import com.mediahub.pear.net.Network
import com.mediahub.pear.servers.MediaServerStore
import com.mediahub.pear.shell.SidebarPins
import com.mediahub.pear.simkl.SimklHistorySync
import com.mediahub.pear.simkl.SimklStore
import com.mediahub.pear.cloud.CloudApi
import com.mediahub.pear.cloud.CloudConfig
import com.mediahub.pear.pair.PairClient
import com.mediahub.pear.pair.PairStore
import com.mediahub.pear.pair.SyncEngine
import com.mediahub.pear.pair.syncsLibraryOverPair
import com.mediahub.pear.party.PartyService
import kotlinx.coroutines.CoroutineDispatcher
import com.mediahub.pear.downloads.DownloadManager
import com.mediahub.pear.downloads.platformDownloadFiles
import com.mediahub.pear.downloads.platformDownloadTransport
import com.mediahub.pear.epochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Storage(
    val prefs: PrefStore,
    val secrets: SecretStore,
    val location: String = "memory (nothing is saved)",
) {
    fun flush() = prefs.flush()

    companion object {
        fun inMemory() = Storage(PrefStore(), InMemorySecretStore())
    }
}

class AppData(
    val storage: Storage,
    val network: Network = Network.offline(),
    addonDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    autoStartAddons: Boolean = true,
) {
    val prefs: PrefStore get() = storage.prefs
    val secrets: SecretStore get() = storage.secrets
    val profiles = ProfileStore(storage.prefs)
    val content = ContentPrefs(storage.prefs, profiles.activeID)

    val meta = MetaServices(network, storage.prefs, storage.secrets)
    val classifier = TitleClassifier.forTmdb(meta.tmdb, network.facts)

    val mediaServers = MediaServerStore(storage.prefs, storage.secrets, network.http)

    /** P2P: the switch, the network check and the one engine (4.5b). Off until the user turns it on. */
    val p2p = P2PService(storage.prefs)
    val addonClient = AddonClient(network.http, network.cache, streamFilter = { p2p.gate(it) })
    val addons = AddonStore(storage.prefs, addonClient, addonDispatcher, autoStart = autoStartAddons).apply {
        serverAddons = { mediaServers.addons }
    }

    val pinned = PinnedSources(storage.prefs)

    /** Downloads: one file each in the downloads folder. Its own single-threaded scope, so the list changes in one order. */
    val downloads = DownloadManager(
        storage.prefs,
        platformDownloadFiles(),
        platformDownloadTransport(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        now = { epochMillis() },
    )
    val subtitles = OpenSubtitlesClient(
        http = network.http,
        enabled = { storage.prefs.bool(PrefKeys.SUBS_ONLINE, true) },
        baseUrl = { storage.prefs.string(PrefKeys.SUBS_BASE_URL) },
    )

    /** Online subtitles for the player: OpenSubtitles plus the add-ons that offer them (4.4b). */
    val subtitleSources = SubtitleSources(network.http, subtitles, { addons.enabledAddons })

    val history = WatchHistory(storage.prefs, profiles.activeID)
    val watchLog = WatchLog(storage.prefs, profiles.activeID)
    val library = LocalLibrary(storage.prefs, profiles.activeID)
    val libraryPrefs = LibraryPrefs(storage.prefs, profiles.activeID)
    /** Titles pinned to the sidebar. Per profile. */
    val sidebarPins = SidebarPins(storage.prefs, profiles.activeID)
    val simkl = SimklStore(network.http, storage.secrets, network.facts)

    /** Progress made in other apps, read back from Simkl. Writes only what Simkl says is newer. */
    val simklHistory = SimklHistorySync(
        simkl,
        history,
        usesSimkl = { libraryPrefs.usesSimkl(simkl.isConnected) },
        minutesOf = { item -> meta.tmdb.cachedDetails(item.id, item.type)?.minutes },
    )

    /** The UI thread's scope: the sync engine and the settings reloads run on it. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Set by the app shell (App.kt): re-reads the theme after a synced setting changed it. The theme lives in the shell,
     * not here, so the shell supplies this.
     */
    var onThemeSettingsSynced: () -> Unit = {}

    /** Pear.pair: the account on this device and the engine that keeps it in step. Idle until an account is signed in here. */
    val pairStore = PairStore(storage.prefs, storage.secrets)
    val pairClient = PairClient(CloudApi(network.http, CloudConfig.pairBaseUrl))
    val pairSync = SyncEngine(
        store = pairStore,
        client = pairClient,
        prefs = storage.prefs,
        activeProfile = { profiles.activeID },
        usesSimkl = { profile -> !syncsLibraryOverPair(storage.prefs, profile, simkl.isConnected) },
        onRemoteApplied = { reloadAfterSync() },
        onSettingsApplied = { keys -> reloadAfterSettings(keys) },
        scope = uiScope,
        flushWatchLog = { watchLog.flush() },
    )

    init {
        // Starts the sync timers when an account is signed in here. Otherwise it reads no secret and does nothing.
        pairSync.startIfSignedIn()
    }

    /** Watch party: the room this device is in and its socket. Idle until a room is started or joined. */
    val party = PartyService(network.http, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    val intro = IntroClient(
        network.http,
        enabled = { storage.prefs.bool(PrefKeys.SKIP_ENABLED, true) },
        tmdbId = { id, type -> meta.tmdb.tmdbIdentifier(id, type) },
    )

    val episodes = EpisodeLoader(meta.tmdb, meta.tvdb)

    /** After a pull changed local data: re-read the stores the screens show. Runs on the UI thread (the engine's scope). */
    private fun reloadAfterSync() {
        profiles.reload()
        val profile = profiles.activeID
        history.reload(profile)
        // Keeps the seconds not yet saved. A plain reload drops up to a minute of playback on every pull that changes data.
        watchLog.reloadKeepingUnsaved(profile)
        library.reload(profile)
        libraryPrefs.reload(profile)
    }

    /**
     * After synced settings changed here: re-read what they drive. Runs on the UI thread (the engine's scope). The add-on
     * list reloads in the background, as it does after a settings import.
     */
    private fun reloadAfterSettings(keys: Set<String>) {
        // Per-profile content settings: the active profile is the one loaded here.
        if (keys.any { it == PrefKeys.CONTENT_PREFS || it.startsWith("${PrefKeys.CONTENT_PREFS}.") }) content.reload()
        // Every add-on key starts with "addon.".
        if (keys.any { it.startsWith("addon.") }) uiScope.launch { addons.reloadFromPrefs() }
        // Appearance keys start with "ui.".
        if (keys.any { it.startsWith("ui.") }) onThemeSettingsSynced()
    }

    fun switchProfile(profile: String) {
        content.load(profile)
        history.load(profile)
        watchLog.load(profile)
        library.load(profile)
        libraryPrefs.load(profile)
        sidebarPins.load(profile)
    }

    fun close() {
        flush()
        mediaServers.close()
        addons.close()
        simkl.close()
    }

    fun flush() {
        meta.flush()
        classifier.flushOnExit()
        watchLog.flush()
        mediaServers.flush()
        storage.flush()
    }
}
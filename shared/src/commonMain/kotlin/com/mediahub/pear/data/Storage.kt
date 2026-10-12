package com.mediahub.pear.data

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
import com.mediahub.pear.simkl.SimklStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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
) {
    val prefs: PrefStore get() = storage.prefs
    val secrets: SecretStore get() = storage.secrets
    val profiles = ProfileStore(storage.prefs)
    val content = ContentPrefs(storage.prefs, profiles.activeID)

    val meta = MetaServices(network, storage.prefs, storage.secrets)
    val classifier = TitleClassifier.forTmdb(meta.tmdb, network.facts)

    val mediaServers = MediaServerStore(storage.prefs, storage.secrets, network.http)

    val addonClient = AddonClient(network.http, network.cache)
    val addons = AddonStore(storage.prefs, addonClient, addonDispatcher).apply {
        serverAddons = { mediaServers.addons }
    }

    val pinned = PinnedSources(storage.prefs)
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
    val simkl = SimklStore(network.http, storage.secrets, network.facts)

    val intro = IntroClient(
        network.http,
        enabled = { storage.prefs.bool(PrefKeys.SKIP_ENABLED, true) },
        tmdbId = { id, type -> meta.tmdb.tmdbIdentifier(id, type) },
    )

    val episodes = EpisodeLoader(meta.tmdb, meta.tvdb)

    fun switchProfile(profile: String) {
        content.load(profile)
        history.load(profile)
        watchLog.load(profile)
        library.load(profile)
        libraryPrefs.load(profile)
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
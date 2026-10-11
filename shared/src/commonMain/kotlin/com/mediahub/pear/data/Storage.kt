package com.mediahub.pear.data

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.AddonStore
import com.mediahub.pear.addons.OpenSubtitlesClient
import com.mediahub.pear.addons.PinnedSources
import com.mediahub.pear.history.EpisodeLoader
import com.mediahub.pear.history.LibraryPrefs
import com.mediahub.pear.history.LocalLibrary
import com.mediahub.pear.history.WatchHistory
import com.mediahub.pear.history.WatchLog
import com.mediahub.pear.meta.IntroClient
import com.mediahub.pear.meta.MetaServices
import com.mediahub.pear.simkl.SimklStore
import com.mediahub.pear.meta.TitleClassifier
import com.mediahub.pear.net.Network
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** What the platform provides: settings persistence and secret storage. Built in each platform's entry point. */
class Storage(
    val prefs: PrefStore,
    val secrets: SecretStore,
    /** Where it lives, for the Storage check screen. */
    val location: String = "memory (nothing is saved)",
) {
    fun flush() = prefs.flush()

    companion object {
        fun inMemory() = Storage(PrefStore(), InMemorySecretStore())
    }
}

/**
 * The data layer's stores, created once per app run. Grows with Batch 2.4.
 * [addonDispatcher] is where add-on changes run, one at a time (tests pass a test dispatcher).
 */
class AppData(
    val storage: Storage,
    val network: Network = Network.offline(),
    addonDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    val prefs: PrefStore get() = storage.prefs
    val secrets: SecretStore get() = storage.secrets
    val profiles = ProfileStore(storage.prefs)
    val content = ContentPrefs(storage.prefs, profiles.activeID)

    /** TMDB, TheTVDB and MDBList (Batch 2.2). */
    val meta = MetaServices(network, storage.prefs, storage.secrets)

    /** Applies the content rules to lists of titles (genres and origin come from TMDB, once per title). */
    val classifier = TitleClassifier.forTmdb(meta.tmdb, network.facts)

    /** Add-on manifests, catalogues, `meta` and streams (Batch 2.3). */
    val addonClient = AddonClient(network.http, network.cache)

    /** The installed add-ons. Starts restoring them from `addon.manifestURLs` right away. */
    val addons = AddonStore(storage.prefs, addonClient, addonDispatcher)

    /** One pinned source per show (`pinned.sources`). */
    val pinned = PinnedSources(storage.prefs)

    /** Online subtitles (`subs.online`, `subs.baseURL`). */
    val subtitles = OpenSubtitlesClient(
        http = network.http,
        enabled = { storage.prefs.bool(PrefKeys.SUBS_ONLINE, true) },
        baseUrl = { storage.prefs.string(PrefKeys.SUBS_BASE_URL) },
    )

    // MARK: Per-profile data (Batch 2.4). Each follows the active profile: see [switchProfile].

    /** Progress per title: Continue Watching, resume, Up Next. */
    val history = WatchHistory(storage.prefs, profiles.activeID)

    /** Seconds actually watched per day and hour (profile statistics). */
    val watchLog = WatchLog(storage.prefs, profiles.activeID)

    /** The profile's own watchlist, used when Simkl isn't the library. */
    val library = LocalLibrary(storage.prefs, profiles.activeID)

    /** Which library the profile uses and the state of the last Simkl sync. */
    val libraryPrefs = LibraryPrefs(storage.prefs, profiles.activeID)

    /** Simkl sign-in, library and scrobbling. Shared by all profiles. */
    val simkl = SimklStore(network.http, storage.secrets, network.facts)

    /** Skip-intro times (`skip.enabled`). */
    val intro = IntroClient(
        network.http,
        enabled = { storage.prefs.bool(PrefKeys.SKIP_ENABLED, true) },
        tmdbId = { id, type -> meta.tmdb.tmdbIdentifier(id, type) },
    )

    /** A season's episodes (TMDB, with TheTVDB filling gaps): Up Next and the detail page. */
    val episodes = EpisodeLoader(meta.tmdb, meta.tvdb)

    /** Points every per-profile store at [profile]. Call when the active profile changes. */
    fun switchProfile(profile: String) {
        content.load(profile)
        history.load(profile)
        watchLog.load(profile)
        library.load(profile)
        libraryPrefs.load(profile)
    }

    /** For when the app goes away: saves what is pending and stops background work. */
    fun close() {
        flush()
        addons.close()
        simkl.close()
    }

    /** Everything that is only saved after a short delay goes to disk now. */
    fun flush() {
        meta.flush()
        classifier.flushOnExit()
        watchLog.flush()
        storage.flush()
    }
}

package com.mediahub.pear.addons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.Addon
import com.mediahub.pear.net.MetaException
import com.mediahub.pear.net.attempt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A public metadata-only add-on, so Home isn't empty on first launch. */
const val DEFAULT_ADDON_URL = "https://v3-cinemeta.strem.io/manifest.json"

/**
 * The installed add-ons (spec: `AddonStore`). Reading is plain Compose state, so screens update by themselves. Every
 * change runs on [dispatcher], one at a time, so the launch restore (which fills the list in as manifests arrive)
 * never races a change the user makes; changes are therefore `suspend`.
 *
 * Saved under the iOS keys: `addon.manifestURLs` (every saved address, in order), `addon.disabled`,
 * `addon.catalogPicker`. Add-on addresses can hold API keys (debrid add-ons); the iOS app keeps them in the settings
 * file too (its own TODO), so the backup format stays the same.
 *
 * Not here: the signed-in Jellyfin/Plex servers that Swift's `enabledAddons` puts first (not in the port plan).
 */
class AddonStore(
    private val prefs: PrefStore,
    private val client: AddonClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val defaults: List<String> = listOf(DEFAULT_ADDON_URL),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var restoreJob: Job? = null

    /** Every installed add-on, in the user's order (the order is saved). */
    var addons: List<Addon> by mutableStateOf(emptyList())
        private set

    /**
     * Installed add-ons the user switched off. They stay installed and keep their place in the order, but are skipped
     * everywhere add-ons are used (Home, search, sources).
     */
    var disabledIds: Set<String> by mutableStateOf(loadSet(PrefKeys.ADDON_DISABLED))
        private set

    /**
     * Add-ons whose catalogues the user picks one by one instead of getting them all on Home. AIOMetadata is recognised
     * on its own ([Addon.isAIOMetadata]); this holds the ones switched on by hand (a copy hosted under another name).
     */
    var pickerIds: Set<String> by mutableStateOf(loadSet(PrefKeys.ADDON_CATALOG_PICKER))
        private set

    /** Bumps after a reload so Home refetches catalogues even though the add-on ids are unchanged. */
    var revision: Int by mutableIntStateOf(0)
        private set

    /**
     * Every saved add-on address, in order: installed, not-yet-loaded and unreachable ones alike. This (not [addons])
     * is what gets written, so a half-finished launch restore can never overwrite the saved list.
     */
    var known: List<String> by mutableStateOf(prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS) ?: defaults)
        private set

    /** True while the launch restore is still fetching manifests. */
    var isRestoring: Boolean by mutableStateOf(true)
        private set

    init {
        restoreJob = scope.launch { restore(known) }
    }

    // MARK: Reading

    /** Installed add-ons that are switched on, in the user's order. */
    val enabledAddons: List<Addon> get() = addons.filter { it.id !in disabledIds }

    /** Saved add-ons whose manifest couldn't be fetched (offline, server asleep). [retryUnreachable] tries again. */
    val unreachable: List<String>
        get() {
            if (isRestoring) return emptyList()
            val loaded = addons.map { it.manifestUrl }.toSet()
            return known.filter { it !in loaded }
        }

    fun isEnabled(addon: Addon): Boolean = addon.id !in disabledIds

    /** True when this add-on's catalogues are added to Home one by one instead of all showing by default. */
    fun usesCatalogPicker(addon: Addon): Boolean = addon.isAIOMetadata || addon.id in pickerIds

    /**
     * The add-on Home's theme rows are enriched with: a switched-on AIOMetadata (or one marked as such by hand) that
     * serves `meta`. null when there isn't one, and the rows just use TMDB.
     */
    val enrichmentAddon: Addon?
        get() = enabledAddons.firstOrNull { a -> usesCatalogPicker(a) && (a.manifest.resources?.any { it.name == "meta" } ?: false) }

    // MARK: Changing

    /** Treats an add-on like AIOMetadata even though it doesn't say so by name (a self-hosted copy). */
    suspend fun setCatalogPicker(addon: Addon, on: Boolean) = withContext(dispatcher) {
        pickerIds = if (on) pickerIds + addon.id else pickerIds - addon.id
        prefs.setStringList(PrefKeys.ADDON_CATALOG_PICKER, pickerIds.sorted())
    }

    /** Switches an add-on on or off without uninstalling it. */
    suspend fun setEnabled(addon: Addon, on: Boolean) = withContext(dispatcher) {
        disabledIds = if (on) disabledIds - addon.id else disabledIds + addon.id
        persistDisabled()
    }

    /** Moves an installed add-on to position [to] (the saved order follows). */
    suspend fun move(from: Int, to: Int) = withContext(dispatcher) {
        if (from !in addons.indices) return@withContext
        val list = addons.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        addons = list
        // Installed ones take the new order; unreachable ones stay saved, after them.
        val order = list.map { it.manifestUrl }
        known = order + known.filter { it !in order }
        persist()
    }

    /**
     * Installs the add-on at [input] (an address, `stremio://...`, or a page that holds `manifest.json`). Throws
     * [com.mediahub.pear.net.BadUrlException] for something that isn't an address, and whatever the fetch throws when
     * the add-on can't be read. Does nothing when it is installed already.
     */
    suspend fun add(input: String) = withContext(dispatcher) {
        restoreJob?.join() // never save while the launch restore is mid-flight
        val url = Addon.normalize(input)
        if (addons.any { it.manifestUrl == url }) return@withContext
        val manifest = client.manifest(url, fresh = true)
        if (addons.any { it.manifestUrl == url }) return@withContext // added meanwhile
        if (url !in known) known = known + url
        addons = addons + Addon(url, manifest)
        sortToKnown()
        persist()
    }

    /** Re-fetches one add-on's manifest (ignoring what is kept) and refreshes its catalogues. */
    suspend fun reload(addon: Addon) = withContext(dispatcher) {
        client.clearCache()
        val m = client.manifest(addon.manifestUrl, fresh = true)
        val i = addons.indexOfFirst { it.id == addon.id }
        if (i < 0) throw MetaException("The add-on is no longer installed")
        addons = addons.toMutableList().also { it[i] = Addon(addon.manifestUrl, m) }
        revision += 1
    }

    /** Reloads every add-on. Returns how many failed (those keep their old manifest). */
    suspend fun reloadAll(): Int = withContext(dispatcher) {
        restoreJob?.join()
        client.clearCache()
        var failed = 0
        for (a in addons) {
            val m = attempt { client.manifest(a.manifestUrl, fresh = true) }
            val i = addons.indexOfFirst { it.id == a.id }
            if (m != null && i >= 0) {
                addons = addons.toMutableList().also { it[i] = Addon(a.manifestUrl, m) }
            } else {
                failed++
            }
        }
        // Add-ons that never loaded aren't in `addons`, so the loop above can't reach them: retry them too.
        retryUnreachable()
        failed += unreachable.size
        revision += 1
        failed
    }

    /**
     * Tries again for saved add-ons that couldn't be fetched earlier. Call when the app comes back to the front or the
     * connection returns. Without this, an add-on that missed launch stays hidden until a restart.
     */
    suspend fun retryUnreachable() = withContext(dispatcher) {
        restoreJob?.join()
        val pending = unreachable
        if (pending.isEmpty()) return@withContext
        var got = false
        for (s in pending) {
            val url = attempt { Addon.normalize(s) } ?: continue
            val m = attempt { client.manifest(url, fresh = true) } ?: continue
            if (addons.any { it.manifestUrl == url }) continue
            addons = addons + Addon(url, m)
            got = true
        }
        if (got) {
            sortToKnown()
            revision += 1
        }
    }

    /** Re-reads the saved settings (after a settings import, Batch 2.5). */
    suspend fun reloadFromPrefs() = withContext(dispatcher) {
        disabledIds = loadSet(PrefKeys.ADDON_DISABLED)
        pickerIds = loadSet(PrefKeys.ADDON_CATALOG_PICKER)
        restoreJob?.join()
        known = prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS) ?: defaults
        addons = emptyList()
        val job = scope.launch { restore(known) }
        restoreJob = job
        job.join()
        revision += 1
    }

    suspend fun remove(addon: Addon) = withContext(dispatcher) {
        addons = addons.filter { it.id != addon.id }
        known = known.filter { it != addon.id }
        disabledIds = disabledIds - addon.id
        pickerIds = pickerIds - addon.id
        persist()
        persistDisabled()
        prefs.setStringList(PrefKeys.ADDON_CATALOG_PICKER, pickerIds.sorted())
    }

    /** Stops the background work. The saved settings are not touched. */
    fun close() {
        scope.cancel()
    }

    // MARK: Saving

    private fun persist() = prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, known)

    private fun persistDisabled() = prefs.setStringList(PrefKeys.ADDON_DISABLED, disabledIds.sorted())

    private fun loadSet(key: String): Set<String> = prefs.stringList(key)?.toSet() ?: emptySet()

    private fun sortToKnown() {
        val pos = HashMap<String, Int>()
        known.forEachIndexed { i, s -> pos.putIfAbsent(s, i) }
        addons = addons.sortedBy { pos[it.manifestUrl] ?: Int.MAX_VALUE }
    }

    /**
     * Fetches every saved manifest. Add-ons appear as each one answers; a failure only leaves that one out (it stays in
     * [known], so it is still saved and [retryUnreachable] can bring it back).
     */
    private suspend fun restore(urls: List<String>) {
        isRestoring = true
        try {
            coroutineScope {
                for (s in urls) {
                    launch {
                        val url = attempt { Addon.normalize(s) } ?: return@launch
                        val m = attempt { client.manifest(url) } ?: return@launch
                        val a = Addon(url, m)
                        // Skips ones removed meanwhile, and ones already there.
                        if (a.id in known && addons.none { it.id == a.id }) {
                            addons = addons + a
                            sortToKnown()
                        }
                    }
                }
            }
        } finally {
            isRestoring = false
        }
    }
}

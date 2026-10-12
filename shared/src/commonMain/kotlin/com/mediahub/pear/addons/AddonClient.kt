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

const val DEFAULT_ADDON_URL = "https://v3-cinemeta.strem.io/manifest.json"

class AddonStore(
    private val prefs: PrefStore,
    private val client: AddonClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val defaults: List<String> = listOf(DEFAULT_ADDON_URL),
    private val serverAddons: () -> List<Addon> = { emptyList() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var restoreJob: Job? = null

    var addons: List<Addon> by mutableStateOf(emptyList())
        private set

    var disabledIds: Set<String> by mutableStateOf(loadSet(PrefKeys.ADDON_DISABLED))
        private set

    var pickerIds: Set<String> by mutableStateOf(loadSet(PrefKeys.ADDON_CATALOG_PICKER))
        private set

    var revision: Int by mutableIntStateOf(0)
        private set

    var known: List<String> by mutableStateOf(prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS) ?: defaults)
        private set

    var isRestoring: Boolean by mutableStateOf(true)
        private set

    init {
        if (prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS) == null) {
            persist()
        }
        restoreJob = scope.launch { restore(known) }
    }

    val enabledInstalledAddons: List<Addon> get() = addons.filter { it.id !in disabledIds }

    /** Everything sources, search, and Home draw from: signed-in media servers first, then installed enabled add-ons. */
    val enabledAddons: List<Addon> get() = serverAddons() + enabledInstalledAddons

    val unreachable: List<String>
        get() {
            if (isRestoring) return emptyList()
            val loaded = addons.map { it.manifestUrl }.toSet()
            return known.filter { it !in loaded }
        }

    fun isEnabled(addon: Addon): Boolean = addon.id !in disabledIds

    fun usesCatalogPicker(addon: Addon): Boolean = addon.isAIOMetadata || addon.id in pickerIds

    val enrichmentAddon: Addon?
        get() = enabledAddons.firstOrNull { a -> usesCatalogPicker(a) && (a.manifest.resources?.any { it.name == "meta" } ?: false) }

    suspend fun setCatalogPicker(addon: Addon, on: Boolean) = withContext(dispatcher) {
        pickerIds = if (on) pickerIds + addon.id else pickerIds - addon.id
        prefs.setStringList(PrefKeys.ADDON_CATALOG_PICKER, pickerIds.sorted())
    }

    suspend fun setEnabled(addon: Addon, on: Boolean) = withContext(dispatcher) {
        disabledIds = if (on) disabledIds - addon.id else disabledIds + addon.id
        persistDisabled()
    }

    suspend fun move(from: Int, to: Int) = withContext(dispatcher) {
        if (from !in addons.indices) return@withContext
        val list = addons.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        addons = list
        val order = list.map { it.manifestUrl }
        known = order + known.filter { it !in order }
        persist()
    }

    suspend fun add(input: String) = withContext(dispatcher) {
        restoreJob?.join()
        val url = Addon.normalize(input)
        if (addons.any { it.manifestUrl == url }) return@withContext
        val manifest = client.manifest(url, fresh = true)
        if (addons.any { it.manifestUrl == url }) return@withContext
        if (url !in known) known = known + url
        addons = addons + Addon(url, manifest)
        sortToKnown()
        persist()
    }

    suspend fun reload(addon: Addon) = withContext(dispatcher) {
        client.clearCache()
        val m = client.manifest(addon.manifestUrl, fresh = true)
        val i = addons.indexOfFirst { it.id == addon.id }
        if (i < 0) throw MetaException("The add-on is no longer installed")
        addons = addons.toMutableList().also { it[i] = Addon(addon.manifestUrl, m) }
        revision += 1
    }

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
        retryUnreachable()
        failed += unreachable.size
        revision += 1
        failed
    }

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

    fun close() {
        scope.cancel()
    }

    private fun persist() = prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, known)

    private fun persistDisabled() = prefs.setStringList(PrefKeys.ADDON_DISABLED, disabledIds.sorted())

    private fun loadSet(key: String): Set<String> = prefs.stringList(key)?.toSet() ?: emptySet()

    private fun sortToKnown() {
        val pos = HashMap<String, Int>()
        known.forEachIndexed { i, s -> pos.putIfAbsent(s, i) }
        addons = addons.sortedBy { pos[it.manifestUrl] ?: Int.MAX_VALUE }
    }

    private suspend fun restore(urls: List<String>) {
        isRestoring = true
        try {
            coroutineScope {
                for (s in urls) {
                    launch {
                        val url = attempt { Addon.normalize(s) } ?: return@launch
                        val m = attempt { client.manifest(url) } ?: return@launch
                        val a = Addon(url, m)
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
package com.mediahub.pear.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.addons.AddonStore
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.UpNext
import com.mediahub.pear.history.UpNextItem
import com.mediahub.pear.localParts
import com.mediahub.pear.meta.ThemeCatalog
import com.mediahub.pear.meta.ThemeRow
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeModel(private val data: AppData) {
    var rows: List<CatalogRow> by mutableStateOf(emptyList())
        private set
    var suggested: List<CatalogRow> by mutableStateOf(emptyList())
        private set
    /** The MDBList rows: one per list the user picked in Settings. See [loadLists]. */
    var lists: List<CatalogRow> by mutableStateOf(emptyList())
        private set
    var upNext: List<UpNextItem> by mutableStateOf(emptyList())
        private set
    /** [upNext] limited to shows that are still Up Next candidates, so a dismissed card leaves at once. */
    val visibleUpNext: List<UpNextItem>
        get() {
            val ids = data.history.finishedEntries.map { it.id }.toSet()
            return upNext.filter { it.item.id in ids }
        }
    var themes: List<ThemeRow> by mutableStateOf(emptyList())
        private set
    var hero: List<MetaPreview> by mutableStateOf(emptyList())
        private set

    var heroDay: Int by mutableIntStateOf(currentDay())
        private set
    var isLoading: Boolean by mutableStateOf(true)
        private set

    /** Add-on catalogues still being fetched. Empty once every one has answered. */
    var pendingCatalogs: List<Pair<Addon, AddonManifest.CatalogDef>> by mutableStateOf(emptyList())
        private set

    /** Add-on catalogues that could not be fetched (offline, error, timeout). Empty catalogues are not failures. */
    var failedCatalogs: List<Pair<Addon, AddonManifest.CatalogDef>> by mutableStateOf(emptyList())
        private set

    /** The add-ons that are still loading, once each, in add-on order. */
    val loadingAddons: List<Addon> get() = pendingCatalogs.map { it.first }.distinctBy { it.id }

    /** The add-ons that failed to provide data, once each, in add-on order. An add-on that is retrying is not listed. */
    val failedAddons: List<Addon> get() = failedCatalogs.map { it.first }.distinctBy { it.id }

    /** Every add-on catalogue Home asked for, in order: the order the rows appear in. */
    private var addonJobs: List<Pair<Addon, AddonManifest.CatalogDef>> = emptyList()

    companion object {
        fun currentDay(): Int {
            val p = localParts(epochMillis())
            return p.year * 365 + p.month * 30 + p.day
        }
    }

    fun refreshHeroDay() {
        val d = currentDay()
        if (d != heroDay) heroDay = d
    }

    suspend fun refresh(force: Boolean = false) {
        isLoading = true
        refreshHeroDay()
        val rules = data.content.rules

        coroutineScope {
            val suggestionsJob = async { loadSuggestions(rules) }
            val rowsJob = async { loadAddonRows(rules) }
            val upNextJob = async { loadUpNext() }
            val listsJob = async { loadLists(rules) }
            // Add-on enrichment of the theme rows is started in this scope and is not waited for below: it can finish
            // after Home has stopped loading, and is cancelled with the rest when the profile changes.
            val themesJob = async { loadThemes(rules, this@coroutineScope) }

            suggestionsJob.await()
            rowsJob.await()
            upNextJob.await()
            listsJob.await()
            themesJob.await()
            updateHero()
            isLoading = false
        }
    }

    private suspend fun loadSuggestions(rules: ContentRules) {
        if (!data.meta.tmdb.hasKey) {
            suggested = emptyList()
            return
        }

        val lastWatched = data.history.lastWatched
        val movies = attempt { data.meta.tmdb.trending("movie") } ?: emptyList()
        val shows = attempt { data.meta.tmdb.trending("tv") } ?: emptyList()
        val because = if (lastWatched != null) attempt { data.meta.tmdb.recommendations(lastWatched.id, lastWatched.type) } ?: emptyList() else emptyList()

        val mFiltered = data.classifier.filter(movies, rules)
        val sFiltered = data.classifier.filter(shows, rules)
        val bFiltered = data.classifier.filter(because, rules)

        val out = mutableListOf<CatalogRow>()
        if (lastWatched != null && bFiltered.isNotEmpty()) {
            out.add(
                CatalogRow(
                    id = "because",
                    title = "Because you watched ${lastWatched.name}",
                    items = bFiltered,
                    symbol = "sparkles",
                    prefKey = BuiltinRows.BECAUSE,
                ),
            )
        }
        if (mFiltered.isNotEmpty()) {
            out.add(
                CatalogRow(
                    id = "trend-movie",
                    title = "Trending Movies",
                    items = mFiltered,
                    source = CatalogSource.TmdbTrending("movie"),
                    symbol = "flame.fill",
                    prefKey = BuiltinRows.TRENDING_MOVIES,
                ),
            )
        }
        if (sFiltered.isNotEmpty()) {
            out.add(
                CatalogRow(
                    id = "trend-tv",
                    title = "Trending Shows",
                    items = sFiltered,
                    source = CatalogSource.TmdbTrending("tv"),
                    symbol = "flame.fill",
                    prefKey = BuiltinRows.TRENDING_SHOWS,
                ),
            )
        }
        suggested = out
    }

    /**
     * The MDBList rows (iOS `HomeView.loadLists`): one row per list the user picked in Settings, in list order. Needs an
     * MDBList key. On a first load each row appears as its list arrives. On a refresh the rows on screen stay until the
     * new ones are ready.
     */
    suspend fun loadLists(rules: ContentRules) {
        val mdb = data.meta.mdblist
        val selected = data.prefs.string(PrefKeys.MDBLIST_LISTS, "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        if (!mdb.hasKey || selected.isEmpty()) {
            lists = emptyList()
            return
        }

        val chosen = mdb.userLists().filter { it.id in selected }
        val progressive = lists.isEmpty()
        val done = mutableMapOf<Int, CatalogRow>()
        val lock = Mutex()
        coroutineScope {
            chosen.forEachIndexed { i, list ->
                launch {
                    val items = data.classifier.filter(mdb.items(list.id), rules)
                    if (items.isEmpty()) return@launch
                    lock.withLock {
                        done[i] = CatalogRow(id = "mdb-${list.id}", title = list.name, items = items, symbol = "list.star")
                        if (progressive) lists = inListOrder(done)
                    }
                }
            }
        }

        // Nothing came back (MDBList unreachable): keep the rows on screen, minus any the user has just deselected.
        if (done.isEmpty() && lists.isNotEmpty()) {
            val wanted = selected.map { "mdb-$it" }.toSet()
            lists = refiltered(lists.filter { it.id in wanted }, rules)
            return
        }
        lists = inListOrder(done)
    }

    private fun inListOrder(done: Map<Int, CatalogRow>): List<CatalogRow> = done.keys.sorted().mapNotNull { done[it] }

    /** The rows with their titles filtered again. A row left with no titles is dropped (iOS `refiltered`). */
    private suspend fun refiltered(old: List<CatalogRow>, rules: ContentRules): List<CatalogRow> =
        old.mapNotNull { row ->
            val items = data.classifier.filter(row.items, rules)
            if (items.isEmpty()) {
                null
            } else {
                CatalogRow(
                    id = row.id,
                    title = row.title,
                    items = items,
                    source = row.source,
                    symbol = row.symbol,
                    prefKey = row.prefKey,
                    rawCount = row.rawCount,
                )
            }
        }

    private fun rowId(job: Pair<Addon, AddonManifest.CatalogDef>) = "${job.first.id}/${job.second.type}/${job.second.id}"

    private suspend fun loadAddonRows(rules: ContentRules) {
        val jobs = HomeCatalogs.jobs(data.addons.enabledAddons, rules, data.addons.pickerIds)
        addonJobs = jobs
        pendingCatalogs = jobs
        failedCatalogs = emptyList()
        // On a first load the rows appear as each add-on answers. On a refresh the rows already on screen stay until
        // the new ones are ready, so a slow add-on doesn't blank Home.
        val progressive = rows.isEmpty()
        try {
            val res = HomeCatalogs.loadRows(
                client = data.addonClient,
                jobs = jobs,
                filter = { data.classifier.filter(it, rules) },
                onProgress = { found -> if (progressive) rows = found },
                onOutcome = { i, _ -> pendingCatalogs = pendingCatalogs - jobs[i] },
            )
            if (!res.allFailed || rows.isEmpty()) {
                rows = res.rows
            }
            failedCatalogs = res.failedJobs
        } finally {
            // Cancelled (the profile changed, or Home was left): nothing is loading any more.
            pendingCatalogs = emptyList()
        }
    }

    /**
     * Tries the catalogues of [addon] that failed again, or of every failed add-on when [addon] is null. The rows that
     * arrive slot into their place among the rows Home already shows. Whatever fails again stays in [failedCatalogs].
     */
    suspend fun retryFailed(addon: Addon? = null) {
        val retry = failedCatalogs.filter { addon == null || it.first.id == addon.id }
        if (retry.isEmpty()) return
        val rules = data.content.rules
        failedCatalogs = failedCatalogs - retry.toSet()
        pendingCatalogs = pendingCatalogs + retry
        try {
            val res = HomeCatalogs.loadRows(
                client = data.addonClient,
                jobs = retry,
                filter = { data.classifier.filter(it, rules) },
                onOutcome = { i, _ -> pendingCatalogs = pendingCatalogs - retry[i] },
            )
            val byId = (rows + res.rows).associateBy { it.id }
            val order = addonJobs.map { rowId(it) }
            rows = order.mapNotNull { byId[it] }
            failedCatalogs = failedCatalogs + res.failedJobs
        } finally {
            pendingCatalogs = pendingCatalogs - retry.toSet()
        }
    }

    private suspend fun loadUpNext() {
        val finished = data.history.finishedEntries
        if (finished.isEmpty()) {
            upNext = emptyList()
            return
        }

        val resolved = mutableListOf<UpNextItem>()
        for (entry in finished) {
            val item = UpNext.resolve(
                entry = entry,
                load = { season -> data.episodes.load(entry.id, "series", season) { if (entry.id.startsWith("tt")) entry.id else null } },
            )
            if (item != null) resolved.add(item)
        }
        upNext = resolved
    }

    /** Resolves Up Next again from the current history. Called when the history changes. */
    suspend fun refreshUpNext() = loadUpNext()

    /** Returns once the rows have loaded; the add-on's enrichment carries on in [enrichScope]. */
    private suspend fun loadThemes(rules: ContentRules, enrichScope: CoroutineScope) {
        ThemeCatalog.loadProgressive(
            tmdb = data.meta.tmdb,
            count = ThemeCatalog.HOME_CANDIDATES,
            dayNumber = heroDay,
            rules = rules,
            http = data.network.http,
            enrichWith = data.addons.enrichmentAddon,
            addonClient = data.addonClient,
            enrichScope = enrichScope,
            update = { themes = it },
        )
    }

    private fun updateHero() {
        val m = suggested.firstOrNull { it.id == "trend-movie" }?.items ?: emptyList()
        val t = suggested.firstOrNull { it.id == "trend-tv" }?.items ?: emptyList()
        val mixed = mutableListOf<MetaPreview>()
        val maxLen = maxOf(m.size, t.size)
        for (i in 0 until maxLen) {
            if (i < m.size) mixed.add(m[i])
            if (i < t.size) mixed.add(t[i])
        }
        val pool = mixed.ifEmpty { rows.firstOrNull()?.items ?: emptyList() }
            .filter { it.backdropUrl != null || it.posterUrl != null }

        if (pool.size <= 7) {
            hero = pool
            return
        }
        val start = (heroDay * 7) % pool.size
        hero = (0 until 7).map { pool[(start + it) % pool.size] }
    }
}

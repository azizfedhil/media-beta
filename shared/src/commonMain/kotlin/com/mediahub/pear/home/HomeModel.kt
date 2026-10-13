package com.mediahub.pear.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.addons.AddonStore
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.UpNext
import com.mediahub.pear.history.UpNextItem
import com.mediahub.pear.localParts
import com.mediahub.pear.meta.ThemeCatalog
import com.mediahub.pear.meta.ThemeRow
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HomeModel(private val data: AppData) {
    var rows: List<CatalogRow> by mutableStateOf(emptyList())
        private set
    var suggested: List<CatalogRow> by mutableStateOf(emptyList())
        private set
    var upNext: List<UpNextItem> by mutableStateOf(emptyList())
        private set
    var themes: List<ThemeRow> by mutableStateOf(emptyList())
        private set
    var hero: List<MetaPreview> by mutableStateOf(emptyList())
        private set

    var heroDay: Int by mutableIntStateOf(currentDay())
        private set
    var isLoading: Boolean by mutableStateOf(true)
        private set

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
            // Add-on enrichment of the theme rows is started in this scope and is not waited for below: it can finish
            // after Home has stopped loading, and is cancelled with the rest when the profile changes.
            val themesJob = async { loadThemes(rules, this@coroutineScope) }

            suggestionsJob.await()
            rowsJob.await()
            upNextJob.await()
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

    private suspend fun loadAddonRows(rules: ContentRules) {
        val jobs = HomeCatalogs.jobs(data.addons.enabledAddons, rules, data.addons.pickerIds)
        val res = HomeCatalogs.loadRows(
            client = data.addonClient,
            jobs = jobs,
            filter = { data.classifier.filter(it, rules) },
        )
        if (!res.allFailed || rows.isEmpty()) {
            rows = res.rows
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
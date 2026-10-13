package com.mediahub.pear.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.mediahub.pear.shell.DragLazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.hidesGenre
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.home.PosterCard
import com.mediahub.pear.home.ThemeCarousel
import com.mediahub.pear.meta.ThemeCatalog
import com.mediahub.pear.meta.ThemeRow
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.model.DiscoverSort
import com.mediahub.pear.model.Genre
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen(
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf("movie") }
    var selectedGenre by remember { mutableStateOf<Int?>(null) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var sort by remember { mutableStateOf(DiscoverSort.Popular) }

    val items = remember { mutableStateListOf<MetaPreview>() }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var themes by remember { mutableStateOf<List<ThemeRow>>(emptyList()) }

    var page by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }

    val hasFilters = selectedGenre != null || selectedYear != null || sort != DiscoverSort.Popular

    suspend fun reload() {
        items.clear()
        page = 0
        hasMore = true
        isLoading = false
    }

    LaunchedEffect(kind) {
        selectedGenre = null
        genres = env.data.meta.tmdb.genres(kind)
        reload()
    }

    LaunchedEffect(Unit) {
        ThemeCatalog.loadProgressive(
            tmdb = env.data.meta.tmdb,
            count = ThemeCatalog.EXPLORE_CANDIDATES,
            offset = ThemeCatalog.HOME_CANDIDATES,
            limit = 12,
            rules = env.data.content.rules,
            dayNumber = 0,
            http = env.data.network.http,
            enrichWith = env.data.addons.enrichmentAddon,
            addonClient = env.data.addonClient,
            enrichScope = this,
            update = { themes = it },
        )
    }

    suspend fun loadMore() {
        if (!hasMore || isLoading || !env.data.meta.tmdb.hasKey) return
        isLoading = true
        var added = 0
        var attempts = 0
        while (added < 12 && attempts < 4 && hasMore) {
            attempts++
            val next = page + 1
            val raw = if (!hasFilters) {
                try { env.data.meta.tmdb.trending(kind, page = next) } catch (_: Exception) { emptyList() }
            } else {
                try {
                    env.data.meta.tmdb.discover(
                        kind = kind,
                        genre = selectedGenre,
                        year = selectedYear,
                        sort = sort,
                        page = next,
                        rules = env.data.content.rules,
                    )
                } catch (_: Exception) { emptyList() }
            }
            page = next
            hasMore = raw.isNotEmpty() && page < 40
            val kept = env.data.classifier.filter(raw, env.data.content.rules)
            val known = items.map { it.id }.toSet()
            val fresh = kept.filter { it.id !in known }
            items.addAll(fresh)
            added += fresh.size
        }
        isLoading = false
    }

    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && !isLoading && total > 0 && lastVisible >= total - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) loadMore()
    }

    val visibleGenres = genres.filter { !env.data.content.rules.hidesGenre(it.id) }
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (LocalWideLayout.current) 165.dp else 110.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp, bottom = bottomInset + 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Type segmented picker: Movies vs Shows
                    Row(
                        Modifier.clip(RoundedCornerShape(PearRadii.pill))
                            .background(PearTheme.colors.fillSubtle)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DevChip("Movies", kind == "movie") { kind = "movie" }
                        DevChip("Shows", kind == "tv") { kind = "tv" }
                    }

                    // Genre chips row
                    DragLazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            DevChip("All genres", selectedGenre == null) {
                                selectedGenre = null
                                scope.launch { reload(); loadMore() }
                            }
                        }
                        items(visibleGenres) { g ->
                            DevChip(g.name, selectedGenre == g.id) {
                                selectedGenre = g.id
                                scope.launch { reload(); loadMore() }
                            }
                        }
                    }

                    // Sort and Reset row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DiscoverSort.entries.forEach { s ->
                                DevChip(s.label, sort == s) {
                                    sort = s
                                    scope.launch { reload(); loadMore() }
                                }
                            }
                        }
                        if (hasFilters) {
                            DevChip("Reset", false) {
                                selectedGenre = null
                                selectedYear = null
                                sort = DiscoverSort.Popular
                                scope.launch { reload(); loadMore() }
                            }
                        }
                    }

                    // Themed editorial collections when unfiltered
                    if (!hasFilters && themes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            themes.forEach { themeRow ->
                                ThemeCarousel(themeRow, onSelectTitle)
                            }
                        }
                    }

                    // Section Heading
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        PearIcon(if (hasFilters) "line.3.horizontal.decrease.circle.fill" else "flame.fill", size = 18.dp, tint = PearTheme.colors.accent)
                        val title = if (hasFilters) {
                            val gName = genres.firstOrNull { it.id == selectedGenre }?.name
                            listOfNotNull(gName, if (kind == "tv") "Shows" else "Movies").joinToString(" · ")
                        } else {
                            if (kind == "tv") "Trending Shows" else "Trending Movies"
                        }
                        PearText(title, style = PearTheme.type.title3.weight(FontWeight.Bold))
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                PosterCard(
                    item = item,
                    width = null,
                    showsTitle = true,
                    onClick = { onSelectTitle(item) },
                )
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        PearText("Loading more…", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                    }
                }
            }
        }

        GlassTopBar(
            title = "Explore",
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        )
    }
}
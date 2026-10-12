package com.mediahub.pear.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

enum class CatalogOrder(val label: String) {
    Original("Original order"),
    Newest("Newest first"),
    Oldest("Oldest first"),
}

@Composable
fun CatalogGridScreen(
    row: CatalogRow,
    onBack: () -> Unit,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<MetaPreview>().apply { addAll(row.items) } }
    var order by remember { mutableStateOf(CatalogOrder.Original) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember {
        mutableStateOf(
            when (row.source) {
                is CatalogSource.None -> false
                is CatalogSource.TmdbTrending -> true
                is CatalogSource.FromAddon -> row.source.catalog.supportsSkip
            },
        )
    }
    var tmdbPage by remember { mutableIntStateOf(1) }
    var fetched by remember { mutableIntStateOf(row.rawCount ?: row.items.size) }
    val seen = remember { row.items.map { it.id }.toMutableSet() }

    suspend fun loadMore() {
        if (!hasMore || isLoading) return
        isLoading = true
        var added = 0
        var attempts = 0
        while (hasMore && added < 12 && attempts < 4) {
            attempts++
            val fresh = when (val src = row.source) {
                is CatalogSource.None -> emptyList()
                is CatalogSource.TmdbTrending -> {
                    val p = try { env.data.meta.tmdb.trending(src.kind, tmdbPage + 1) } catch (_: Exception) { emptyList() }
                    if (p.isNotEmpty()) tmdbPage++
                    p
                }
                is CatalogSource.FromAddon -> {
                    val cat = try { env.data.addonClient.catalog(src.addon, src.catalog, skip = fetched) } catch (_: Exception) { emptyList() }
                    fetched += cat.size
                    cat
                }
            }
            val newItems = fresh.filter { seen.add(it.id) }
            hasMore = newItems.isNotEmpty()
            val kept = env.data.classifier.filter(newItems, env.data.content.rules)
            items.addAll(kept)
            added += kept.size
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

    val orderedItems = remember(items.toList(), order) {
        when (order) {
            CatalogOrder.Original -> items.toList()
            CatalogOrder.Newest -> items.sortedByDescending { it.year ?: 0 }
            CatalogOrder.Oldest -> items.sortedBy { it.year ?: 9999 }
        }
    }

    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp, bottom = bottomInset + 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(orderedItems, key = { it.id }) { item ->
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
            title = row.title,
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CatalogOrder.entries.forEach { o ->
                        DevChip(o.label, order == o) { order = o }
                    }
                }
            },
        )
    }
}
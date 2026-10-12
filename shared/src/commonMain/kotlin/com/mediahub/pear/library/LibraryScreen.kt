package com.mediahub.pear.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.mediahub.pear.shell.DragLazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.home.PosterCard
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    onSelectTitle: (MetaPreview) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val usingSimkl = env.data.libraryPrefs.usesSimkl(env.data.simkl.isConnected)

    val rows = remember(usingSimkl, env.data.simkl.library, env.data.library.entries, env.data.history.entries) {
        if (usingSimkl) {
            env.data.simkl.library
        } else {
            env.data.library.rows(env.data.history.continueWatching)
        }
    }

    var selectedFilter by remember { mutableStateOf("all") }
    val collapsedSections = remember { mutableStateOf(setOf<String>()) }
    val bottomInset = LocalBottomInset.current

    val displayedRows = if (selectedFilter == "all") rows else rows.filter { it.id.contains(selectedFilter) }

    Box(modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearIcon("books.vertical.fill", size = 48.dp, tint = PearTheme.colors.textTertiary)
                    PearText(
                        if (usingSimkl) "Your Simkl library is empty." else "Your library is empty.",
                        style = PearTheme.type.headline,
                    )
                    PearText(
                        "Bookmark titles to add them to your watchlist.",
                        style = PearTheme.type.subheadline,
                        color = PearTheme.colors.textSecondary,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (LocalWideLayout.current) 165.dp else 110.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp, bottom = bottomInset + 32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Section Filter Chips Row
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DragLazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val total = rows.sumOf { it.items.size }
                        item {
                            DevChip("All ($total)", selectedFilter == "all") { selectedFilter = "all" }
                        }
                        items(rows) { r ->
                            val isSel = selectedFilter in r.id
                            DevChip("${r.title} (${r.items.size})", isSel) {
                                selectedFilter = if (isSel) "all" else r.id.substringAfter("simkl-").substringAfter("local-").substringBefore('-')
                            }
                        }
                    }
                }

                // Render each library section
                displayedRows.forEach { row ->
                    val isCollapsed = collapsedSections.value.contains(row.id) && selectedFilter == "all"
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (selectedFilter == "all") {
                                    val current = collapsedSections.value
                                    collapsedSections.value = if (current.contains(row.id)) current - row.id else current + row.id
                                }
                            }.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.symbol?.let { PearIcon(it, size = 16.dp, tint = PearTheme.colors.accent) }
                                PearText(row.title, style = PearTheme.type.title3.weight(FontWeight.Bold))
                                PearText("(${row.items.size})", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                            }
                            if (selectedFilter == "all") {
                                PearIcon(if (isCollapsed) "chevron.right" else "chevron.down", size = 14.dp, tint = PearTheme.colors.textSecondary)
                            }
                        }
                    }

                    if (!isCollapsed) {
                        items(row.items, key = { "${row.id}-${it.id}" }) { item ->
                            PosterCard(item = item, width = null, showsTitle = true, onClick = { onSelectTitle(item) })
                        }
                    }
                }
            }
        }

        GlassTopBar(
            title = "Library",
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            trailing = {
                GlassButton(
                    onClick = onOpenDownloads,
                    symbol = "arrow.down.circle",
                    size = 38.dp,
                )
            },
        )
    }
}
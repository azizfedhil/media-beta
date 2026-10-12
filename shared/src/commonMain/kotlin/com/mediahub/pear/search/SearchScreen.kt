package com.mediahub.pear.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import com.mediahub.pear.shell.DragLazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.home.PosterCard
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

enum class SearchScope(val label: String) {
    All("All"),
    Movies("Movies"),
    Shows("Shows"),
}

@Composable
fun SearchScreen(
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.All) }
    val results = remember { mutableStateListOf<MetaPreview>() }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results.clear()
            isSearching = false
            hasSearched = false
            return@LaunchedEffect
        }
        delay(350)
        isSearching = true
        hasSearched = true

        val addons = env.data.addons.enabledAddons
        val searchableJobs = addons.flatMap { a ->
            a.manifest.catalogs.orEmpty().filter { it.isSearchable }.map { a to it }
        }.take(6)

        val tmdbDeferred = async { env.data.meta.tmdb.search(q) }
        val out = mutableListOf<MetaPreview>()
        val ids = mutableSetOf<String>()
        val byName = mutableMapOf<String, Int>()

        fun add(m: MetaPreview) {
            if (m.type != "movie" && m.type != "series") return
            val key = "${m.name.lowercase()}|${m.year ?: ""}"
            val existing = byName[key]
            if (existing != null) {
                if (out[existing].rating == null && m.rating != null) {
                    out[existing] = out[existing].withRating(m.rating)
                }
                return
            }
            if (!ids.add(m.id)) return
            byName[key] = out.size
            out.add(m)
        }

        for ((addon, cat) in searchableJobs) {
            val list = try { env.data.addonClient.catalog(addon, cat, search = q) } catch (_: Exception) { emptyList<MetaPreview>() }
            list.forEach { add(it) }
        }

        val tmdb = tmdbDeferred.await()
        tmdb.forEach { add(it) }

        val filtered = if (env.data.content.rules.filterSearch) {
            env.data.classifier.filter(out, env.data.content.rules)
        } else {
            out
        }

        results.clear()
        results.addAll(filtered)
        isSearching = false
    }

    val movies = results.filter { it.type == "movie" }
    val shows = results.filter { it.type == "series" }
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .padding(top = 92.dp, bottom = bottomInset + 20.dp)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextInput(
                value = query,
                onChange = { query = it },
                placeholder = "Search movies and shows…",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchScope.entries.forEach { s ->
                    DevChip(s.label, scope == s) { scope = s }
                }
            }

            if (query.trim().length < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcon("magnifyingglass", size = 48.dp, tint = PearTheme.colors.textTertiary)
                        PearText("Find movies and shows across your add-ons and TMDB.", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                    }
                }
            } else if (isSearching && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PearText("Searching…", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
            } else if (hasSearched && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PearText("No titles found for \"$query\".", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (scope != SearchScope.Shows && movies.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PearIcon("film", size = 18.dp, tint = PearTheme.colors.accent)
                                    PearText("Movies (${movies.size})", style = PearTheme.type.title3.weight(FontWeight.Bold))
                                }
                                DragLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(movies) { m ->
                                        PosterCard(item = m, showsTitle = true, onClick = { onSelectTitle(m) })
                                    }
                                }
                            }
                        }
                    }

                    if (scope != SearchScope.Movies && shows.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PearIcon("tv", size = 18.dp, tint = PearTheme.colors.accent)
                                    PearText("Shows (${shows.size})", style = PearTheme.type.title3.weight(FontWeight.Bold))
                                }
                                DragLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(shows) { s ->
                                        PosterCard(item = s, showsTitle = true, onClick = { onSelectTitle(s) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(title = "Search", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
    }
}
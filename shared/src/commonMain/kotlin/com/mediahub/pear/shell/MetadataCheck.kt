package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.DiscoverSort
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round

private fun describe(m: MetaPreview): String {
    val year = m.releaseInfo?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
    val rating = m.rating?.let { " rating ${round(it * 10) / 10}" } ?: ""
    return "${m.name}$year$rating  [${m.type}]  ${m.id}"
}

private fun list(title: String, items: List<MetaPreview>): String =
    if (items.isEmpty()) "$title: nothing" else "$title: ${items.size} titles\n" + items.take(6).joinToString("\n") { "  - ${describe(it)}" }

/**
 * Settings -> Developer -> Metadata check (Batch 2.2). Every call goes through the same clients the real screens will
 * use, so this shows whether trending, search and a title's details load, how long they take, and whether the second
 * ask is served from the cache. Keys typed here are the ones Settings will edit in Batch 3.5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetadataCheckScreen(data: AppData, onBack: () -> Unit) {
    val c = PearTheme.colors
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("dune") }
    var keyTick by remember { mutableIntStateOf(0) } // re-reads the key status after a save
    var last by remember { mutableStateOf<MetaPreview?>(null) }
    val tmdb = data.meta.tmdb

    fun run(label: String, block: suspend () -> String) {
        scope.launch {
            val start = epochMillis()
            val text = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "FAILED: ${e::class.simpleName}: ${e.message}"
            }
            log.add(0, "$label  (${epochMillis() - start} ms)\n$text")
        }
    }

    fun titles(label: String, load: suspend () -> List<MetaPreview>) = run(label) {
        val items = load()
        if (items.isNotEmpty()) last = items.first()
        list(label, items)
    }

    PageScaffold("Metadata check", onBack = onBack) {
        PageCard {
            PearText("TMDB", style = PearTheme.type.headline)
            val source = if (keyTick >= 0 && tmdb.usesOwnKey) "your own key (straight to TMDB)" else if (tmdb.hasProxy) "the built-in proxy (no key needed)" else "nothing: no key and no proxy"
            PearText("Requests go through: $source", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Selected title: ${last?.let { describe(it) } ?: "none yet (run trending or search)"}", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Trending movies", false) { titles("Trending movies") { tmdb.trending("movie") } }
                DevChip("Trending series", false) { titles("Trending series") { tmdb.trending("tv") } }
                DevChip("Popular movies", false) { titles("Discover: popular movies") { tmdb.discover("movie", null, null, DiscoverSort.Popular, 1, data.content.rules) } }
                DevChip("Top rated series", false) { titles("Discover: top rated series") { tmdb.discover("tv", null, null, DiscoverSort.TopRated, 1, data.content.rules) } }
                DevChip("Newest movies", false) { titles("Discover: newest movies") { tmdb.discover("movie", null, null, DiscoverSort.Newest, 1, data.content.rules) } }
                DevChip("K-drama theme", false) { run("Themed: K-drama") { tmdb.themed(listOf("korean drama"), "ko", 8, data.content.rules).let { r -> "${r.size} titles\n" + r.joinToString("\n") { "  - ${describe(it.item)}  ${it.genres}" } } } }
                DevChip("Genres (movie)", false) { run("Genres") { tmdb.genres("movie").joinToString { "${it.name} ${it.id}" }.ifEmpty { "nothing" } } }
            }
            TextInput(query, { query = it }, "Search text")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Search", false) { titles("Search \"$query\"") { tmdb.search(query) } }
            }
        }
        PageCard {
            PearText("Selected title", style = PearTheme.type.headline)
            PearText("Uses the title chosen above. Series-only calls need a series.", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Details", false) {
                    val m = last ?: return@DevChip
                    run("Details ${m.id}") {
                        val d = tmdb.details(m.id, m.type)
                        "${d.tagline.orEmpty()}\nruntime ${d.minutes} min, ${d.genres?.joinToString { it.name }}\n" +
                            "cast: ${d.credits?.cast?.take(5)?.joinToString { it.name }}\nseasons: ${d.numberOfSeasons ?: "-"}, budget ${d.budget ?: "-"}, revenue ${d.revenue ?: "-"}\n" +
                            d.overview.orEmpty().take(200)
                    }
                }
                DevChip("Season 1", false) {
                    val m = last ?: return@DevChip
                    run("Episodes ${m.id} S1") { tmdb.episodes(m.id, m.type, 1).let { e -> "${e.size} episodes\n" + e.take(4).joinToString("\n") { "  - ${it.episodeNumber}. ${it.name} ${it.stillUrl != null}" } } }
                }
                DevChip("More like this", false) {
                    val m = last ?: return@DevChip
                    run("Recommendations ${m.id}") { list("More like this", tmdb.recommendations(m.id, m.type)) }
                }
                DevChip("Logo, network, trailers", false) {
                    val m = last ?: return@DevChip
                    run("Logo ${m.id}") {
                        "logo: ${tmdb.logo(m.id, m.type)}\nnetwork: ${tmdb.network(m.id, m.type)}\ntrailers: ${tmdb.videos(m.id, m.type).take(3).joinToString { "${it.type} ${it.name}" }}"
                    }
                }
                DevChip("IMDb id", false) {
                    val m = last ?: return@DevChip
                    val n = m.id.removePrefix("tmdb:").toIntOrNull() ?: return@DevChip
                    run("IMDb id of ${m.id}") { "${tmdb.imdbId(n, m.type)}" }
                }
                DevChip("Traits (filter)", false) {
                    val m = last ?: return@DevChip
                    run("Traits ${m.id}") { "${tmdb.traits(m.id, m.type)}" }
                }
            }
        }
        PageCard {
            PearText("TheTVDB and MDBList", style = PearTheme.type.headline)
            PearText("Both need a key of your own (below). They work on the selected title's IMDb id.", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("TVDB episodes S1", false) {
                    val m = last ?: return@DevChip
                    run("TVDB episodes ${m.id}") {
                        if (!data.meta.tvdb.hasKey) return@run "No TVDB key set."
                        val imdb = imdbOf(tmdb, m) ?: return@run "No IMDb id for ${m.id}."
                        data.meta.tvdb.episodes(imdb, 1).let { e -> "$imdb: ${e.size} episodes\n" + e.take(4).joinToString("\n") { "  - ${it.number}. ${it.name} ${it.imageUrl != null}" } }
                    }
                }
                DevChip("TVDB logo", false) {
                    val m = last ?: return@DevChip
                    run("TVDB logo ${m.id}") {
                        if (!data.meta.tvdb.hasKey) return@run "No TVDB key set."
                        val imdb = imdbOf(tmdb, m) ?: return@run "No IMDb id for ${m.id}."
                        "$imdb: ${data.meta.tvdb.logo(imdb, m.type)}"
                    }
                }
                DevChip("MDBList ratings", false) {
                    val m = last ?: return@DevChip
                    run("MDBList ratings ${m.id}") {
                        if (!data.meta.mdblist.hasKey) return@run "No MDBList key set."
                        val imdb = imdbOf(tmdb, m) ?: return@run "No IMDb id for ${m.id}."
                        data.meta.mdblist.ratings(imdb, m.type).joinToString("\n") { "${it.label}: ${it.text}" }.ifEmpty { "$imdb: no ratings" }
                    }
                }
                DevChip("MDBList lists", false) {
                    run("MDBList lists") {
                        if (!data.meta.mdblist.hasKey) return@run "No MDBList key set."
                        data.meta.mdblist.userLists().joinToString("\n") { "${it.id}: ${it.name} (${it.items ?: "?"})" }.ifEmpty { "no lists" }
                    }
                }
            }
        }
        PageCard {
            PearText("Keys (optional)", style = PearTheme.type.headline)
            PearText("TMDB works without one. A key of your own bypasses the proxy. Saved in settings under the iOS key names.", style = PearTheme.type.footnote, color = c.textSecondary)
            KeyField(data, "TMDB key", PrefKeys.TMDB_KEY, keyTick) { keyTick++ }
            KeyField(data, "TheTVDB key", PrefKeys.TVDB_KEY, keyTick) { keyTick++ }
            KeyField(data, "TheTVDB PIN", PrefKeys.TVDB_PIN, keyTick) { keyTick++ }
            KeyField(data, "MDBList key", PrefKeys.MDBLIST_KEY, keyTick) { keyTick++ }
        }
        PageCard {
            PearText("Caches", style = PearTheme.type.headline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Show", false) { run("Caches") { "answers: ${data.network.cache.describe()}\nfacts: ${tmdb.describe()}" } }
                DevChip("Clear answers", false) { run("Clear answers") { data.network.cache.clear(); "Cleared. The next call goes to the network." } }
                DevChip("Save facts now", false) { run("Save facts") { tmdb.flushPending(); "Written." } }
                DevChip("Clear log", false) { log.clear() }
            }
            PearText("Ask for the same thing twice: the second time should be much faster (served from the cache).", style = PearTheme.type.footnote, color = c.textSecondary)
        }
        log.forEach { entry ->
            PageCard { PearText(entry, style = PearTheme.type.footnote) }
        }
    }
}

private suspend fun imdbOf(tmdb: com.mediahub.pear.meta.TmdbClient, m: MetaPreview): String? {
    if (m.id.startsWith("tt")) return m.id
    val n = m.id.removePrefix("tmdb:").toIntOrNull() ?: return null
    return tmdb.imdbId(n, m.type)
}

@Composable
internal fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.card)).background(c.fill).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) PearText(placeholder, style = PearTheme.type.body, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PearTheme.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyField(data: AppData, label: String, prefKey: String, tick: Int, onSaved: () -> Unit) {
    val c = PearTheme.colors
    var text by remember { mutableStateOf("") }
    val current = if (tick >= 0) data.prefs.string(prefKey, "") else ""
    PearText("$label: ${if (current.isEmpty()) "not set" else "set (${current.length} characters)"}", style = PearTheme.type.footnote, color = c.textSecondary)
    TextInput(text, { text = it }, "Paste $label")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DevChip("Save", false) { if (text.isNotBlank()) { data.prefs.setString(prefKey, text.trim()); text = ""; onSaved() } }
        DevChip("Remove", false) { data.prefs.remove(prefKey); onSaved() }
    }
}

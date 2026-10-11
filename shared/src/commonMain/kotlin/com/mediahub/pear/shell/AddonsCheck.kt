package com.mediahub.pear.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.HomeCatalogs
import com.mediahub.pear.addons.OpenSubtitlesClient
import com.mediahub.pear.addons.Pin
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.net.attempt
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import io.ktor.http.Url
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Add-on addresses can hold API keys, so the screens show the host only. */
private fun hostOf(url: String): String = attempt { Url(url).host } ?: "?"

/**
 * Settings -> Developer -> Add-ons check (Batch 2.3). Installs, orders and switches add-ons, loads catalogues and
 * streams through the same clients the real screens will use, and shows how long each add-on took.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddonsCheckScreen(data: AppData, onBack: () -> Unit) {
    val c = PearTheme.colors
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var address by remember { mutableStateOf("") }
    var titleId by remember { mutableStateOf("tt0133093") }
    var kind by remember { mutableStateOf("movie") }
    var searchText by remember { mutableStateOf("dune") }
    var streams by remember { mutableStateOf<List<Pair<Addon, List<StreamItem>>>>(emptyList()) }
    val store = data.addons
    val client = data.addonClient

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

    PageScaffold("Add-ons check", onBack = onBack) {
        PageCard {
            PearText("Installed add-ons", style = PearTheme.type.headline)
            val state = when {
                store.isRestoring -> "restoring saved add-ons..."
                else -> "${store.addons.size} installed, ${store.enabledAddons.size} switched on, ${store.unreachable.size} unreachable"
            }
            PearText(state, style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Only the host is shown: add-on addresses can hold API keys.", style = PearTheme.type.footnote, color = c.textSecondary)
            store.unreachable.forEach { PearText("Unreachable: ${hostOf(it)}", style = PearTheme.type.footnote, color = c.textSecondary) }
            TextInput(address, { address = it }, "Add-on address (https://..., stremio://... or a page with manifest.json)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Add", false) {
                    val text = address
                    if (text.isNotBlank()) run("Add ${hostOf(text)}") { store.add(text); address = ""; "Installed. ${store.addons.size} add-ons now." }
                }
                DevChip("Reload all", false) { run("Reload all") { val failed = store.reloadAll(); "$failed failed" } }
                DevChip("Retry unreachable", false) { run("Retry unreachable") { store.retryUnreachable(); "${store.unreachable.size} still unreachable" } }
                DevChip("Clear add-on answers", false) { run("Clear add-on answers") { client.clearCache(); "Cleared for this run." } }
                DevChip("Clear log", false) { log.clear() }
            }
        }
        store.addons.forEachIndexed { index, addon ->
            PageCard {
                val m = addon.manifest
                PearText(m.name + (m.version?.let { " $it" } ?: ""), style = PearTheme.type.headline)
                val resources = m.resources.orEmpty().joinToString { it.name }.ifEmpty { "none" }
                val on = if (store.isEnabled(addon)) "on" else "off"
                PearText(
                    "${hostOf(addon.manifestUrl)}  |  $on  |  resources: $resources  |  ${m.catalogs.orEmpty().size} catalogues, ${addon.homeCatalogs.size} browsable",
                    style = PearTheme.type.footnote, color = c.textSecondary,
                )
                val flags = listOfNotNull(
                    if (addon.isAIOMetadata) "AIOMetadata" else null,
                    if (addon.needsConfiguration) "needs setup" else if (addon.isConfigurable) "has a setup page" else null,
                    if (store.enrichmentAddon?.id == addon.id) "enriches theme rows" else null,
                )
                if (flags.isNotEmpty()) PearText(flags.joinToString(", "), style = PearTheme.type.footnote, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevChip(if (store.isEnabled(addon)) "Switch off" else "Switch on", false) {
                        run("Switch ${m.name}") { store.setEnabled(addon, !store.isEnabled(addon)); "Now ${if (store.isEnabled(addon)) "on" else "off"}." }
                    }
                    DevChip(if (store.usesCatalogPicker(addon)) "Picker: on" else "Picker: off", false) {
                        run("Picker ${m.name}") { store.setCatalogPicker(addon, addon.id !in store.pickerIds); "Picker by hand: ${addon.id in store.pickerIds}" }
                    }
                    DevChip("Up", false) { run("Move up ${m.name}") { store.move(index, index - 1); "Position ${store.addons.indexOfFirst { it.id == addon.id } + 1}" } }
                    DevChip("Down", false) { run("Move down ${m.name}") { store.move(index, index + 1); "Position ${store.addons.indexOfFirst { it.id == addon.id } + 1}" } }
                    DevChip("Reload", false) { run("Reload ${m.name}") { store.reload(addon); "Reloaded." } }
                    DevChip("Remove", false) { run("Remove ${m.name}") { store.remove(addon); "Removed. ${store.addons.size} left." } }
                }
            }
        }
        PageCard {
            PearText("Home rows and catalogues", style = PearTheme.type.headline)
            PearText("Uses the active profile's content rules (hidden rows, hidden categories) and the 12-row cap.", style = PearTheme.type.footnote, color = c.textSecondary)
            TextInput(searchText, { searchText = it }, "Catalogue search text")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Home rows", false) {
                    run("Home rows") {
                        val rules = data.content.rules
                        val jobs = HomeCatalogs.jobs(store.enabledAddons, rules, store.pickerIds)
                        val res = HomeCatalogs.loadRows(client, jobs, filter = { data.classifier.filter(it, rules) })
                        "${jobs.size} catalogues asked, ${res.rows.size} rows, ${res.failures} failed${if (res.allFailed) " (all: offline?)" else ""}\n" +
                            res.rows.joinToString("\n") { r ->
                                val from = (r.source as? CatalogSource.FromAddon)?.addon?.manifest?.name ?: "?"
                                "  - ${r.title} [$from]: ${r.items.size} of ${r.rawCount} titles, first: ${r.items.firstOrNull()?.name}"
                            }
                    }
                }
                DevChip("Search catalogues", false) {
                    run("Search \"$searchText\"") {
                        val targets = store.enabledAddons.flatMap { a -> a.manifest.catalogs.orEmpty().filter { it.isSearchable }.map { a to it } }.take(6)
                        if (targets.isEmpty()) return@run "No enabled add-on has a searchable catalogue."
                        targets.map { (a, cat) ->
                            val found = attempt { client.catalog(a, cat, search = searchText) }
                            "  - ${a.manifest.name} / ${cat.name ?: cat.id}: ${found?.size ?: "failed"}${found?.firstOrNull()?.let { " first: ${it.name}" } ?: ""}"
                        }.joinToString("\n")
                    }
                }
                DevChip("Page 2 (skip 100)", false) {
                    run("Skip 100") {
                        val target = store.enabledAddons.firstNotNullOfOrNull { a -> a.homeCatalogs.firstOrNull { it.supportsSkip }?.let { a to it } }
                            ?: return@run "No catalogue declares skip."
                        val page = client.catalog(target.first, target.second, skip = 100)
                        "${target.first.manifest.name} / ${target.second.name ?: target.second.id}: ${page.size} titles, first: ${page.firstOrNull()?.name}"
                    }
                }
            }
        }
        PageCard {
            PearText("Streams, meta and subtitles for one title", style = PearTheme.type.headline)
            PearText("Id: tt0133093 for a movie, tt0944947:1:1 for an episode (series).", style = PearTheme.type.footnote, color = c.textSecondary)
            TextInput(titleId, { titleId = it }, "Title id")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("movie", kind == "movie") { kind = "movie" }
                DevChip("series", kind == "series") { kind = "series" }
                DevChip("Streams", false) {
                    val id = titleId.trim()
                    val type = kind
                    run("Streams $type $id") {
                        val enabled = store.enabledAddons
                        val asked = enabled.count { it.provides("stream", type, id) }
                        val started = epochMillis()
                        val got = ArrayList<Pair<Addon, List<StreamItem>>>()
                        val lines = ArrayList<String>()
                        client.streamUpdates(id, type, enabled).collect { (addon, items) ->
                            got.add(addon to items)
                            lines.add("  ${epochMillis() - started} ms  ${addon.manifest.name}: ${items.size} sources (${items.count { it.url != null }} direct, ${items.count { it.isTorrent }} torrent)")
                        }
                        val ordered = AddonClient.ordered(got, enabled)
                        streams = ordered
                        val sample = ordered.firstOrNull()?.second?.take(3)?.joinToString("\n") { "    * ${it.signature} | ${it.fileExtension.ifEmpty { "?" }}" }.orEmpty()
                        "asked $asked of ${enabled.size} add-ons, ${got.size} answered (in the order they answered)\n" + lines.joinToString("\n") +
                            (if (sample.isEmpty()) "" else "\nfirst add-on in your order:\n$sample")
                    }
                }
                DevChip("Meta extras", false) {
                    val id = titleId.trim()
                    val type = kind
                    run("Meta extras $type $id") {
                        store.enabledAddons.map { a ->
                            val x = client.metaExtras(a, type, id)
                            "  - ${a.manifest.name}: genres ${x.genres.take(4)}, logo ${x.logo != null}"
                        }.joinToString("\n").ifEmpty { "no add-ons" }
                    }
                }
                DevChip("Subtitles", false) {
                    val parts = titleId.trim().split(":")
                    run("Subtitles ${titleId.trim()}") {
                        val all = data.subtitles.search(parts[0], parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull())
                        val shown = OpenSubtitlesClient.choices(all, data.prefs.string(PrefKeys.SUB_LANG, "off"))
                        "${all.size} files, ${all.map { it.languageName }.distinct().size} languages\nplayer list: ${shown.joinToString { it.languageName }}"
                    }
                }
                DevChip("Pin first source", false) {
                    val id = titleId.trim()
                    run("Pin first source") {
                        val first = streams.firstNotNullOfOrNull { (a, items) -> items.firstOrNull()?.let { a to it } } ?: return@run "Run Streams first."
                        data.pinned.set(Pin(first.first.id, first.second.signature), id)
                        "Pinned \"${first.second.signature}\" from ${first.first.manifest.name} for $id"
                    }
                }
                DevChip("Show pin", false) {
                    val id = titleId.trim()
                    run("Pin of $id") { data.pinned.pin(id)?.let { "\"${it.signature}\" from ${hostOf(it.addonID)}" } ?: "no pin" }
                }
                DevChip("Remove pin", false) {
                    val id = titleId.trim()
                    run("Remove pin") { data.pinned.remove(id); "Removed." }
                }
            }
        }
        log.forEach { entry ->
            PageCard { PearText(entry, style = PearTheme.type.footnote) }
        }
    }
}

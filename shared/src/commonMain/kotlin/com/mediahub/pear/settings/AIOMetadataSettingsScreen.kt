package com.mediahub.pear.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.data.isRowAdded
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.net.attempt
import com.mediahub.pear.openUrl
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

@Composable
fun AIOMetadataSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val store = env.data.addons
    val content = env.data.content

    val pickerAddons = store.addons.filter { store.usesCatalogPicker(it) }
    val otherAddons = manualAddonsOf(store.addons)

    var newUrl by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var addNote by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reloading by remember { mutableStateOf(emptySet<String>()) }
    var reloadNotes by remember { mutableStateOf(emptyMap<String, String>()) }

    // The page's cards, named so the wide layout can put the other add-ons beside the catalogues.
    val mainCards: @Composable ColumnScope.() -> Unit = {
        if (pickerAddons.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("No AIOMetadata add-on installed", style = PearTheme.type.headline)
                PearText(
                    "AIOMetadata offers dozens of catalogues. Install it in Settings -> Add-ons, then choose which ones appear on Home here.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
                TextInput(newUrl, { newUrl = it }, "AIOMetadata add-on URL (https:// or stremio://)")
                GlassButton(
                    onClick = {
                        scope.launch {
                            adding = true
                            val url = newUrl.trim()
                            val added = attempt { store.add(url) } != null
                            adding = false
                            if (added) {
                                newUrl = ""
                                addError = null
                                val normalized = attempt { Addon.normalize(url) }
                                val installed = store.addons.firstOrNull { it.manifestUrl == normalized }
                                addNote = installed?.takeIf { !store.usesCatalogPicker(it) }?.let {
                                    "Added ${it.manifest.name}, but it doesn't identify itself as AIOMetadata. Switch it on under Other add-ons."
                                }
                            } else {
                                addError = "Couldn't load that manifest. Check the URL and try again."
                                addNote = null
                            }
                        }
                    },
                    label = if (adding) "Adding…" else "Add AIOMetadata",
                    enabled = newUrl.isNotBlank() && !adding,
                )
                val error = addError
                val note = addNote
                PearText(
                    error ?: note
                        ?: "Open your AIOMetadata page, choose the catalogues you want it to offer, then paste the add-on link it gives you (https:// or stremio://). You then pick which of them appear on Home.",
                    style = PearTheme.type.caption,
                    color = if (error != null) PearTheme.colors.red else PearTheme.colors.textSecondary,
                )
            }
        }

        if (pickerAddons.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                TextInput(query, { query = it }, "Search catalogues")
                if (query.isNotBlank() && pickerAddons.none { catalogGroupsMatching(it.homeCatalogs, query).isNotEmpty() }) {
                    PearText(
                        "No catalogues match \"${query.trim()}\".",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                }
            }
        }

        pickerAddons.forEach { addon ->
            val keys = addon.homeCatalogs.map { addon.rowKey(it) }
            val on = keys.count { content.rules.isRowAdded(it) }
            val busy = addon.id in reloading
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText(
                        manageHeader(addon.manifest.name, enabled = store.isEnabled(addon), on = on),
                        style = PearTheme.type.headline,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    GlassButton(
                        onClick = { content.setRowsAdded(keys, false) },
                        label = "Remove all from Home",
                        enabled = on > 0,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(
                        onClick = {
                            reloading = reloading + addon.id
                            scope.launch {
                                val ok = attempt { store.reload(addon) } != null
                                val message = if (ok) "${addon.manifest.name} reloaded." else "Couldn't reach ${addon.manifest.name}."
                                reloadNotes = reloadNotes + (addon.id to message)
                                reloading = reloading - addon.id
                            }
                        },
                        label = if (busy) "Reloading…" else "Reload catalogues",
                        symbol = "arrow.clockwise",
                        enabled = !busy,
                    )
                    GlassButton(
                        onClick = { openUrl(addon.configureUrl) },
                        label = "Open setup page",
                        symbol = "gearshape",
                    )
                }
                reloadNotes[addon.id]?.let {
                    PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                }
                PearText(manageFooter(addon), style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)

                catalogGroupsMatching(addon.homeCatalogs, query).forEach { group ->
                    PearText(catalogKindLabel(group.type), style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                    group.catalogs.forEach { cat ->
                        val key = addon.rowKey(cat)
                        val isAdded = content.rules.isRowAdded(key)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                PearText(cat.name ?: cat.id, style = PearTheme.type.subheadline)
                                PearText(cat.id, style = PearTheme.type.caption2, color = PearTheme.colors.textSecondary, maxLines = 1)
                            }
                            DevChip(if (isAdded) "On Home" else "Off", isAdded) {
                                content.setRowAdded(key, !isAdded)
                            }
                        }
                    }
                }
            }
        }
    }
    val otherCards: @Composable ColumnScope.() -> Unit = {
        if (otherAddons.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Other add-ons", style = PearTheme.type.headline)
                PearText(
                    "Is your AIOMetadata add-on missing above? A copy hosted under another name isn't recognised automatically. Switch it on here to pick its catalogues too. Switching an add-on on removes its catalogues from Home until you add them.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
                otherAddons.forEach { a ->
                    val on = store.usesCatalogPicker(a)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText(a.manifest.name, style = PearTheme.type.subheadline)
                        DevChip(if (on) "On" else "Off", on) {
                            scope.launch { store.setCatalogPicker(a, !on) }
                        }
                    }
                }
            }
        }
    }
    val cardCount = (if (pickerAddons.isEmpty()) 1 else 1 + pickerAddons.size) + (if (otherAddons.isNotEmpty()) 1 else 0)
    val split = otherAddons.isNotEmpty() && cardCount >= 3

    SettingsPage(title = "AIOMetadata", onBack = onBack, modifier = modifier, maxWidth = if (split) 1000.dp else 720.dp) {
        if (split) {
            SettingsColumns(left = mainCards, right = otherCards)
        } else {
            mainCards()
            otherCards()
        }
    }
}

// The rules behind this screen, kept as plain functions so they can be unit-tested.

/** One section of the catalogue list: an add-on's catalogues of one type. */
internal data class CatalogGroup(val type: String, val catalogs: List<AddonManifest.CatalogDef>)

/** Installed add-ons with catalogues that aren't recognised as AIOMetadata. The user may switch these on by hand. */
internal fun manualAddonsOf(addons: List<Addon>): List<Addon> =
    addons.filter { it.homeCatalogs.isNotEmpty() && !it.isAIOMetadata }

/** "Movies", "Series", or any other type with its first letter capitalised. */
internal fun catalogKindLabel(type: String): String = when (type) {
    "movie" -> "Movies"
    "series" -> "Series"
    else -> type.replaceFirstChar { it.uppercase() }
}

/**
 * The catalogues whose name or id contains [query] (any case, surrounding spaces ignored), grouped by type in the order
 * the add-on lists them. An empty query keeps everything.
 */
internal fun catalogGroupsMatching(catalogs: List<AddonManifest.CatalogDef>, query: String): List<CatalogGroup> {
    val q = query.trim().lowercase()
    val matching = catalogs.filter { cat ->
        q.isEmpty() || (cat.name ?: cat.id).lowercase().contains(q) || cat.id.lowercase().contains(q)
    }
    return matching.map { it.type }.distinct().map { type ->
        CatalogGroup(type, matching.filter { it.type == type })
    }
}

/** The heading over an add-on's controls: its name, "(add-on is off)" when it is switched off, and how many are on Home. */
internal fun manageHeader(name: String, enabled: Boolean, on: Int): String =
    name + (if (enabled) "" else " (add-on is off)") + " · $on on Home"

/** What the Content & catalogues row says: whether AIOMetadata is set up, and how many of its catalogues are on Home. */
internal fun aioPickerSummary(pickerAddons: Int, addedCount: Int): String = when {
    pickerAddons == 0 -> "Not set up"
    addedCount == 0 -> "None on Home"
    else -> "$addedCount on Home"
}

/** Where an add-on's catalogues come from, or why it has none to offer. */
internal fun manageFooter(addon: Addon): String =
    if (addon.homeCatalogs.isEmpty()) {
        "This add-on offers no catalogues that can sit on Home. Add some on its setup page, then reload."
    } else {
        "Catalogues are set up on the add-on's own page. After changing them there, copy the new add-on link, add it in Settings -> Add-ons and remove the old one, or just reload if the link didn't change."
    }
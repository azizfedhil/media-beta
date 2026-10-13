package com.mediahub.pear.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
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
    val bottomInset = LocalBottomInset.current

    val pickerAddons = store.addons.filter { store.usesCatalogPicker(it) }
    val otherAddons = store.addons.filter { !it.homeCatalogs.isEmpty() && !store.usesCatalogPicker(it) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pickerAddons.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("No AIOMetadata add-on installed", style = PearTheme.type.headline)
                    PearText(
                        "AIOMetadata offers dozens of catalogues. Install it in Settings -> Add-ons, then choose which ones appear on Home here.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                }
            }

            pickerAddons.forEach { addon ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText(addon.manifest.name, style = PearTheme.type.headline)
                        DevChip("Remove all from Home", false) {
                            val keys = addon.homeCatalogs.map { addon.rowKey(it) }
                            content.setRowsAdded(keys, false)
                        }
                    }

                    addon.homeCatalogs.forEach { cat ->
                        val key = addon.rowKey(cat)
                        val isAdded = content.rules.addedRows.contains(key)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                PearText(cat.name ?: cat.id, style = PearTheme.type.subheadline)
                                PearText(cat.type, style = PearTheme.type.caption2, color = PearTheme.colors.textSecondary)
                            }
                            DevChip(if (isAdded) "On Home" else "Off", isAdded) {
                                content.setRowAdded(key, !isAdded)
                            }
                        }
                    }
                }
            }

            if (otherAddons.isNotEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Other add-ons", style = PearTheme.type.headline)
                    PearText(
                        "Enable catalog picking for custom or self-hosted add-ons to prevent them from flooding your Home feed.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                    otherAddons.forEach { a ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PearText(a.manifest.name, style = PearTheme.type.subheadline)
                            DevChip("Enable picker", false) {
                                scope.launch { store.setCatalogPicker(a, true) }
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(title = "AIOMetadata", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}
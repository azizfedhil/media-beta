package com.mediahub.pear.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.data.isRowAdded
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.SettingsRow
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ContentSettingsScreen(
    onBack: () -> Unit,
    onNavigateAIOMetadata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val content = env.data.content
    val rules = content.rules

    // AIOMetadata: the way into the catalogue picker. Without this row the screen it opens can't be reached.
    val addons = env.data.addons
    val pickerAddons = addons.addons.filter { addons.usesCatalogPicker(it) }
    val addedCount = pickerAddons.sumOf { a -> a.homeCatalogs.count { rules.isRowAdded(a.rowKey(it)) } }

    SettingsPage(title = "Content & catalogues", onBack = onBack, modifier = modifier, compactSpacing = 20.dp) {
        SettingsColumns(
            left = {
                // Where it's from section
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Where it's from", style = PearTheme.type.headline)
                    PearText("Switch off regions you don't want suggested. Only affects live-action titles.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                    ContentCategory.regions.forEach { cat ->
                        val isHidden = rules.hiddenCategories.contains(cat)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PearIcon("globe", size = 16.dp, tint = PearTheme.colors.accent)
                                PearText(cat.name, style = PearTheme.type.subheadline)
                            }
                            DevChip(if (isHidden) "HIDDEN" else "SHOWN", !isHidden) {
                                content.setHidden(cat, !isHidden)
                            }
                        }
                    }
                }

                // Kinds of titles section
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Kinds of titles", style = PearTheme.type.headline)
                    ContentCategory.entries.filter { !it.isRegion }.forEach { cat ->
                        val isHidden = rules.hiddenCategories.contains(cat)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PearText(cat.name, style = PearTheme.type.subheadline)
                            DevChip(if (isHidden) "HIDDEN" else "SHOWN", !isHidden) {
                                content.setHidden(cat, !isHidden)
                            }
                        }
                    }
                }
            },
            right = {
                // Home rows section
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Home rows", style = PearTheme.type.headline)
                    listOf(
                        "Trending Movies" to BuiltinRows.TRENDING_MOVIES,
                        "Trending Shows" to BuiltinRows.TRENDING_SHOWS,
                        "Because you watched" to BuiltinRows.BECAUSE,
                        "Themed collections" to BuiltinRows.THEMES,
                    ).forEach { (label, key) ->
                        val isHidden = rules.hiddenRows.contains(key)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PearText(label, style = PearTheme.type.subheadline)
                            DevChip(if (isHidden) "OFF" else "ON", !isHidden) {
                                content.setRowHidden(key, !isHidden)
                            }
                        }
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("AIOMetadata", style = PearTheme.type.headline)
                    SettingsRow(
                        title = "AIOMetadata catalogues",
                        subtitle = aioPickerSummary(pickerAddons.size, addedCount),
                        onClick = onNavigateAIOMetadata,
                        trailing = { PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary) },
                    )
                    PearText(
                        "Pick which AIOMetadata catalogues get a row on Home. None show until you add them.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                }

                // Search filter toggle & Reset
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText("Also filter search results", style = PearTheme.type.subheadline)
                        DevChip(if (rules.filterSearch) "ON" else "OFF", rules.filterSearch) {
                            content.setFilterSearch(!rules.filterSearch)
                        }
                    }
                }

                GlassButton(
                    onClick = { content.reset() },
                    label = "Show everything again",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}
package com.mediahub.pear.settings

import androidx.compose.runtime.key
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.net.attempt
import com.mediahub.pear.openUrl
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import io.ktor.http.Url
import kotlinx.coroutines.launch

/** Only the host of an add-on address. The path, query and any user:password may hold an API key, so none of them are shown. */
private fun unreachableHost(url: String): String = attempt { Url(url).host } ?: "?"

@Composable
fun AddonsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val store = env.data.addons

    var newUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var isReloadingAll by remember { mutableStateOf(false) }

    SettingsPage(title = "Add-ons", onBack = onBack, modifier = modifier) {
        SettingsColumns(
            left = {
                // Add new add-on input
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Add an add-on", style = PearTheme.type.headline)
                    TextInput(newUrl, { newUrl = it }, "Manifest URL (https://... or stremio://...)")
                    GlassButton(
                        onClick = {
                            scope.launch {
                                try {
                                    store.add(newUrl.trim())
                                    newUrl = ""
                                    addError = null
                                } catch (e: Exception) {
                                    addError = e.message ?: "Could not load add-on manifest."
                                }
                            }
                        },
                        label = "Add add-on",
                        enabled = newUrl.isNotBlank(),
                    )
                    addError?.let { PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.red) }
                }
            },
            right = {
                // Installed Add-ons List
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText("Installed (${store.addons.size + store.notLoaded.size})", style = PearTheme.type.headline)
                        DevChip(if (isReloadingAll) "Reloading…" else "Reload all", false) {
                            scope.launch {
                                isReloadingAll = true
                                store.reloadAll()
                                isReloadingAll = false
                            }
                        }
                    }

                    store.addons.forEachIndexed { index, addon -> key(addon.id) {
                        val anchor = remember { MenuAnchor() }
                        val isEnabled = store.isEnabled(addon)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).menuAnchor(anchor).clickOrMenu(null, onClick = {}, onMenu = {
                                val items = mutableListOf<ContextMenuItem>()
                                items += ContextMenuItem(
                                    if (isEnabled) "Turn off" else "Turn on",
                                    if (isEnabled) "pause.circle" else "play.circle",
                                ) { store.setEnabled(addon, !isEnabled) }
                                if (addon.isConfigurable) {
                                    items += ContextMenuItem("Configure", "gearshape") { openUrl(addon.configureUrl) }
                                }
                                items += ContextMenuItem("Reload", "arrow.clockwise") { store.reload(addon) }
                                env.shell.openContextMenu(ContextMenuRequest(anchor.rect(), items))
                            }),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                PearText(addon.manifest.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                val desc = addon.manifest.description ?: addon.manifestUrl
                                PearText(desc, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 1)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (addon.isConfigurable) {
                                    GlassButton(onClick = { openUrl(addon.configureUrl) }, symbol = "gearshape", size = 32.dp)
                                }
                                DevChip(if (isEnabled) "ON" else "OFF", isEnabled) {
                                    scope.launch { store.setEnabled(addon, !isEnabled) }
                                }
                                if (index > 0) {
                                    GlassButton(onClick = { scope.launch { store.move(index, index - 1) } }, symbol = "arrow.up", size = 32.dp)
                                }
                                if (index < store.addons.size - 1) {
                                    GlassButton(onClick = { scope.launch { store.move(index, index + 1) } }, symbol = "arrow.down", size = 32.dp)
                                }
                                GlassButton(onClick = { scope.launch { store.remove(addon) } }, symbol = "trash", size = 32.dp)
                            }
                        }
                    }
                    }
                    // Saved add-ons not installed yet: still loading at launch, or they didn't load (offline, server asleep).
                    // They stay saved until Retry works or Delete removes them.
                    if (store.notLoaded.isNotEmpty()) {
                        if (!store.isRestoring) {
                            PearText("Couldn't load (still saved)", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                        }
                        store.notLoaded.forEach { url -> key(url) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PearText(unreachableHost(url), style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), modifier = Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (store.isRestoring) {
                                        PearText("Loading…", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                                    } else {
                                        GlassButton(onClick = { scope.launch { store.retryUnreachable() } }, label = "Retry", symbol = "arrow.clockwise", size = 32.dp)
                                    }
                                    GlassButton(onClick = { scope.launch { store.removeUnreachable(url) } }, symbol = "trash", size = 32.dp)
                                }
                            }
                        } }
                    }
                }
            },
            leftWeight = 0.8f,
            rightWeight = 1.2f,
        )
    }
}
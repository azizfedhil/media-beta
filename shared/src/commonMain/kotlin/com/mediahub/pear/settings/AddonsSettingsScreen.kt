package com.mediahub.pear.settings

import androidx.compose.runtime.key
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
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
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.openUrl
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

@Composable
fun AddonsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val store = env.data.addons
    val bottomInset = LocalBottomInset.current

    var newUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var isReloadingAll by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            // Installed Add-ons List
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Installed (${store.addons.size})", style = PearTheme.type.headline)
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
            }
        }

        GlassTopBar(title = "Add-ons", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}
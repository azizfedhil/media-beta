package com.mediahub.pear.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.SettingsBackup
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupCheckScreen(data: AppData, onBack: () -> Unit) {
    val c = PearTheme.colors
    val scope = rememberCoroutineScope()
    var includeData by remember { mutableStateOf(true) }
    var resultText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Ready.") }
    var tick by remember { mutableIntStateOf(0) }

    fun refreshState() { tick++ }

    PageScaffold("Backup check", onBack = onBack) {
        PageCard {
            PearText("Current State", style = PearTheme.type.headline)
            val currentAccent = data.prefs.string(PrefKeys.UI_ACCENT, "default")
            val tmdbKey = data.prefs.string(PrefKeys.TMDB_KEY, "none")
            val addonsCount = data.addons.known.size
            val profilesCount = data.profiles.profiles.size
            val activeProfile = data.profiles.active.name
            val historyCount = data.history.entries.size
            val hiddenCount = data.content.rules.hiddenCategories.size

            PearText("Active Profile: $activeProfile ($profilesCount total)", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Accent: $currentAccent  ·  TMDB Key: $tmdbKey", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Known Add-ons: $addonsCount  ·  Watch History: $historyCount items", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Hidden Categories: $hiddenCount", style = PearTheme.type.footnote, color = c.textSecondary)
        }

        PageCard {
            PearText("Export", style = PearTheme.type.headline)
            PearText("Status: $statusMessage", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Include data: ${if (includeData) "ON" else "OFF"}", includeData) {
                    includeData = !includeData
                }
                DevChip("Export Plist (XML)", false) {
                    try {
                        val plistBytes = SettingsBackup.exportPlist(data.prefs, includeData)
                        resultText = plistBytes.decodeToString()
                        statusMessage = "Exported Plist (${plistBytes.size} bytes)."
                    } catch (e: Exception) {
                        statusMessage = "Export failed: ${e.message}"
                    }
                }
                DevChip("Import from text above", false) {
                    if (resultText.isBlank()) {
                        statusMessage = "No backup text to import."
                        return@DevChip
                    }
                    scope.launch {
                        try {
                            val count = SettingsBackup.applyBackup(resultText, data)
                            statusMessage = "Successfully imported $count settings!"
                            refreshState()
                        } catch (e: Exception) {
                            statusMessage = "Import error: ${e.message}"
                        }
                    }
                }
            }
        }

        PageCard {
            PearText("Import / Migration", style = PearTheme.type.headline)
            TextInput(resultText, { resultText = it }, "Paste Plist XML or JSON backup text here...")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Import from text above", false) {
                    if (resultText.isBlank()) {
                        statusMessage = "No backup text to import."
                        return@DevChip
                    }
                    scope.launch {
                        try {
                            val count = SettingsBackup.applyBackup(resultText, data)
                            statusMessage = "Successfully imported $count settings!"
                            refreshState()
                        } catch (e: Exception) {
                            statusMessage = "Import error: ${e.message}"
                        }
                    }
                }
                DevChip("Sample iOS Plist", false) {
                    resultText = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>_pearBackupVersion</key>
                            <integer>1</integer>
                            <key>ui.accent</key>
                            <string>#22C55E</string>
                            <key>tmdb.key</key>
                            <string>sample_ios_key_999</string>
                            <key>addon.manifestURLs</key>
                            <array>
                                <string>https://v3-cinemeta.strem.io/manifest.json</string>
                                <string>https://torrentio.strem.fun/manifest.json</string>
                            </array>
                        </dict>
                        </plist>
                    """.trimIndent()
                    statusMessage = "Loaded sample iOS backup plist."
                }
                DevChip("Clear Output", false) {
                    resultText = ""
                    statusMessage = "Cleared."
                }
            }
        }
    }
}
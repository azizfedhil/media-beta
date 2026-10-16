package com.mediahub.pear.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

private const val COUNTER = "dev.counter"
private const val GLOBAL = "dev.global"
private const val SECRET = "dev.secret"
private val swatches = listOf("#FF453A", "#30D158", "#0A84FF", "#FFD60A", "#BF5AF2", "#FF9F0A")

/**
 * Settings -> Developer -> Storage check (Batch 2.1). Proves that values survive a restart, that every profile
 * reads and writes its own data, and that secrets reach the operating system's store.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageCheckScreen(data: AppData, onBack: () -> Unit) {
    val c = PearTheme.colors
    val profiles = data.profiles
    var tick by remember { mutableIntStateOf(0) } // bumps to re-read plain (non-observable) values
    var secretNote by remember { mutableStateOf("") }
    // Reading `tick` inside the function subscribes whichever lambda calls it, so it redraws after a bump.
    fun read(key: String): Int { if (tick < 0) return 0; return data.prefs.int(key, 0) }

    PageScaffold("Storage check", onBack = onBack) {
        PageCard {
            PearText("Where", style = PearTheme.type.headline)
            PearText("Settings: ${data.storage.location}", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Secrets: ${data.secrets.backend}", style = PearTheme.type.footnote, color = c.textSecondary)
            PearText("Keys stored: ${data.prefs.keys.size}", style = PearTheme.type.footnote, color = c.textSecondary)
        }
        PageCard {
            PearText("Shared value", style = PearTheme.type.headline)
            PearText("Counter: ${read(GLOBAL)}. Add one, close the app, open it again: the number must still be there.", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("+1", false) { data.prefs.setInt(GLOBAL, read(GLOBAL) + 1); tick++ }
                DevChip("Reset", false) { data.prefs.remove(GLOBAL); tick++ }
            }
        }
        PageCard {
            PearText("Profiles (${profiles.profiles.size} of ${com.mediahub.pear.data.ProfileStore.LIMIT})", style = PearTheme.type.headline)
            PearText("Each profile has its own counter, history key and content settings. Select one, change it, select another.", style = PearTheme.type.footnote, color = c.textSecondary)
            profiles.profiles.forEach { p ->
                val key = ProfileKeys.scoped(COUNTER, p.id)
                val active = p.id == profiles.activeID
                PearText(
                    "${if (active) "> " else ""}${p.name}  (${p.colorHex})   counter ${read(key)}   key \"$key\"",
                    style = PearTheme.type.footnote.weight(if (active) FontWeight.Bold else FontWeight.Normal),
                    color = if (active) c.textPrimary else c.textSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevChip("Select", active) { profiles.select(p.id) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Add profile", false) {
                    profiles.add("Profile ${profiles.profiles.size + 1}", swatches[profiles.profiles.size % swatches.size], "")
                    tick++
                }
                DevChip("Delete active", false) { profiles.delete(profiles.activeID); tick++ }
            }
        }
        PageCard {
            val id = profiles.activeID
            val key = ProfileKeys.scoped(COUNTER, id)
            val hidden = data.content.rules.hiddenCategories
            PearText("Active profile: ${profiles.active.name}", style = PearTheme.type.headline)
            PearText("Counter ${read(key)}. Hidden categories: ${if (hidden.isEmpty()) "none" else hidden.joinToString { it.raw }}.", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Counter +1", false) { data.prefs.setInt(key, read(key) + 1); tick++ }
                listOf(ContentCategory.Anime, ContentCategory.Horror, ContentCategory.Kids).forEach { cat ->
                    DevChip("Hide ${cat.raw}", cat in hidden) { data.content.setHidden(cat, cat !in hidden) }
                }
                DevChip("Show everything", false) { data.content.reset() }
            }
        }
        PageCard {
            PearText("Secrets", style = PearTheme.type.headline)
            PearText(secretNote.ifEmpty { "Stores a test value, then reads it back." }, style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Store", false) {
                    val protected = data.secrets.set(SECRET, "pear-test-${read(GLOBAL)}")
                    secretNote = if (protected) "Stored in the operating system's protection." else "Stored in the fallback file (the OS keystore refused or isn't available)."
                }
                DevChip("Read", false) { secretNote = "Read back: ${data.secrets.get(SECRET) ?: "nothing"}" }
                DevChip("Remove", false) { data.secrets.remove(SECRET); secretNote = "Removed." }
            }
        }
    }
}

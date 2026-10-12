package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.model.SubLanguages
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun SubtitleSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    val bottomInset = LocalBottomInset.current

    var subsOnline by remember { mutableStateOf(prefs.bool(PrefKeys.SUBS_ONLINE, true)) }
    var selectedLang by remember { mutableStateOf(prefs.string(PrefKeys.SUB_LANG, "off")) }
    var fontSize by remember { mutableFloatStateOf(26f) }
    var fontColor by remember { mutableStateOf(Color.White) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Live Preview Box
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(PearRadii.card))
                    .background(Color(0xFF141418)),
                contentAlignment = Alignment.Center,
            ) {
                PearText(
                    text = "This is how subtitles look",
                    style = PearTheme.type.headline.weight(FontWeight.Bold).copy(fontSize = fontSize.sp),
                    color = fontColor,
                )
            }

            // Online Subtitles Toggle
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("OpenSubtitles online lookup", style = PearTheme.type.headline)
                    DevChip(if (subsOnline) "ON" else "OFF", subsOnline) {
                        subsOnline = it
                        prefs.setBool(PrefKeys.SUBS_ONLINE, it)
                    }
                }
                PearText("Automatically finds subtitles from OpenSubtitles if missing in the video file.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
            }

            // Default Language
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Default language", style = PearTheme.type.headline)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "Off", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German").forEach { (code, label) ->
                        DevChip(label, selectedLang == code) {
                            selectedLang = code
                            prefs.setString(PrefKeys.SUB_LANG, code)
                        }
                    }
                }
            }

            // Appearance Customization
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Appearance", style = PearTheme.type.headline)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Size (${fontSize.toInt()} px)", style = PearTheme.type.subheadline)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DevChip("Small", fontSize == 20f) { fontSize = 20f }
                        DevChip("Medium", fontSize == 26f) { fontSize = 26f }
                        DevChip("Large", fontSize == 34f) { fontSize = 34f }
                    }
                }

                PearText("Colour", style = PearTheme.type.subheadline)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Color.White, Color(0xFFFFD60A), Color(0xFF64D2FF), Color(0xFF30D158), Color(0xFFFF9F0A)).forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .clickable { fontColor = c },
                        )
                    }
                }
            }
        }

        GlassTopBar(title = "Subtitles", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}
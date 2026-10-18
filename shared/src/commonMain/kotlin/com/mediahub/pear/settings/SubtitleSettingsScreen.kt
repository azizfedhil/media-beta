package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import com.mediahub.pear.player.SubtitleStyle
import com.mediahub.pear.player.ui.PlayerSlider
import com.mediahub.pear.player.ui.SubtitleStyleEditor
import com.mediahub.pear.shell.rememberPref

@Composable
fun SubtitleSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs

    var subsOnline by remember { mutableStateOf(prefs.bool(PrefKeys.SUBS_ONLINE, true)) }
    var selectedLang by remember { mutableStateOf(prefs.string(PrefKeys.SUB_LANG, "off")) }
    var selectedAudioLang by remember { mutableStateOf(prefs.string(PrefKeys.AUDIO_LANG, "off")) }
    // The one stored look: the player's own subtitle controls and this page edit the same setting (as on iOS).
    val style = rememberPref(prefs, PrefKeys.SUB_STYLE) { SubtitleStyle.decode(it.string(PrefKeys.SUB_STYLE, "")) }
    fun save(value: SubtitleStyle) = prefs.setString(PrefKeys.SUB_STYLE, value.encoded())

    SettingsPage(title = "Subtitles", onBack = onBack, modifier = modifier) {
        SettingsColumns(
            left = {
                // Online Subtitles Toggle
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText("Online subtitle lookup", style = PearTheme.type.headline)
                        DevChip(if (subsOnline) "ON" else "OFF", subsOnline) {
                            subsOnline = !subsOnline
                            prefs.setBool(PrefKeys.SUBS_ONLINE, subsOnline)
                        }
                    }
                    PearText("Finds subtitles online when the file has none in your language: OpenSubtitles, and the add-ons that offer subtitles.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
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

                // Default audio language: the track it picks on load, when a file has one in that language.
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Default audio language", style = PearTheme.type.headline)
                    PearText(
                        "Picks the audio track in this language when a file has one. Off keeps the file's own default.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("off" to "Off", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German").forEach { (code, label) ->
                            DevChip(label, selectedAudioLang == code) {
                                selectedAudioLang = code
                                prefs.setString(PrefKeys.AUDIO_LANG, code)
                            }
                        }
                    }
                }
            },
            right = {
                // Appearance: the shared editor, as in the player's Subtitles panel. Each change is saved as it is made.
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Appearance", style = PearTheme.type.headline)
                    SubtitleStyleEditor(style, onChange = { save(it) })
                }
            },
        )
    }
}

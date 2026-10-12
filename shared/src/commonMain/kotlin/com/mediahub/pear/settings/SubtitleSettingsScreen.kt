package com.mediahub.pear.settings

import androidx.compose.foundation.background
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
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import com.mediahub.pear.player.SubtitleStyle
import com.mediahub.pear.player.ui.PlayerSlider
import com.mediahub.pear.player.ui.SubtitleLine
import com.mediahub.pear.player.ui.subtitleColor
import com.mediahub.pear.shell.rememberPref
import kotlin.math.min
import kotlin.math.round

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
    var selectedAudioLang by remember { mutableStateOf(prefs.string(PrefKeys.AUDIO_LANG, "off")) }
    // The one stored look: the player's own subtitle controls and this page edit the same setting (as on iOS).
    val style = rememberPref(prefs, PrefKeys.SUB_STYLE) { SubtitleStyle.decode(it.string(PrefKeys.SUB_STYLE, "")) }
    fun save(value: SubtitleStyle) = prefs.setString(PrefKeys.SUB_STYLE, value.encoded())

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            // Appearance: the same controls as iOS. Each change is saved as it is made (the preview follows it).
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Appearance", style = PearTheme.type.headline)
                SubtitlePreview(style)

                StyleRow("Size", "${style.size.toInt()} px") {
                    StyleSlider(style.size, 12.0..72.0, 1.0) { save(style.copy(size = it)) }
                }

                StyleRow("Colour") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SubtitleStyle.colors.forEach { name ->
                            val selected = style.color == name
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(subtitleColor(name))
                                    .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                    .clickable { save(style.copy(color = name)) },
                            )
                        }
                    }
                }

                StyleRow("Font") {
                    ChoiceRow(listOf("system" to "System", "rounded" to "Rounded", "serif" to "Serif", "mono" to "Mono"), style.font) {
                        save(style.copy(font = it))
                    }
                }

                StyleRow("Weight") {
                    ChoiceRow(
                        listOf("regular" to "Reg", "medium" to "Med", "semibold" to "Semi", "bold" to "Bold", "heavy" to "Heavy"),
                        style.weight,
                    ) { save(style.copy(weight = it)) }
                }

                StyleRow("Edge") {
                    ChoiceRow(listOf("none" to "None", "shadow" to "Shadow", "outline" to "Outline"), style.edge) {
                        save(style.copy(edge = it))
                    }
                }

                StyleRow("Background", if (style.boxOpacity < 0.01) "Off" else "${(style.boxOpacity * 100).toInt()}%") {
                    StyleSlider(style.boxOpacity, 0.0..0.9, 0.05) { save(style.copy(boxOpacity = it)) }
                }

                StyleRow("Distance from bottom", "${style.bottom.toInt()} px") {
                    StyleSlider(style.bottom, 0.0..160.0, 2.0) { save(style.copy(bottom = it)) }
                }

                DevChip("Reset to defaults", false) { prefs.setString(PrefKeys.SUB_STYLE, "") }
            }
        }

        GlassTopBar(title = "Subtitles", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

/** The preview: a grey frame like a dark picture, with a line drawn the way the player draws it (size capped at 34). */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    Box(
        Modifier.fillMaxWidth().height(120.dp)
            .clip(RoundedCornerShape(PearRadii.card))
            .background(Brush.verticalGradient(listOf(Color(0xFF474747), Color(0xFF141414)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SubtitleLine(
            "This is how subtitles look",
            style.copy(size = min(style.size, 34.0), bottom = 0.0),
            Modifier.padding(bottom = 10.dp),
        )
    }
}

/** A row of the editor: its title and current value, then the control. */
@Composable
private fun StyleRow(title: String, value: String? = null, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PearText(title, style = PearTheme.type.subheadline)
            if (value != null) PearText(value, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
        }
        control()
    }
}

/** One choice out of a few: the chips the settings pages use. */
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) -> DevChip(label, selected == value) { onPick(value) } }
    }
}

/**
 * A slider over [range], snapped to [step]. While it is dragged the value shows at once; the setting is saved when the drag
 * ends, not on every move.
 */
@Composable
private fun StyleSlider(value: Double, range: ClosedFloatingPointRange<Double>, step: Double, onCommit: (Double) -> Unit) {
    var draft by remember { mutableStateOf<Double?>(null) }
    val shown = draft ?: value
    val span = range.endInclusive - range.start
    PlayerSlider(
        fraction = ((shown - range.start) / span).toFloat(),
        onChange = { f ->
            val raw = range.start + f * span
            draft = (round(raw / step) * step).coerceIn(range.start, range.endInclusive)
        },
        onEditing = { editing ->
            if (!editing) {
                draft?.let(onCommit)
                draft = null
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

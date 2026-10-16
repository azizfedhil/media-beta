package com.mediahub.pear.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.player.SubtitleStyle
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlin.math.min
import kotlin.math.round

/**
 * The subtitle look, edited: a preview, then size, colour, font, weight, edge, background, distance from the bottom, and a
 * reset (iOS: `SubtitleStyleControls`). The Settings page and the player's Subtitles panel both use it, so both edit the
 * same setting and show it the same way. Each change goes to [onChange] as it is made; a slider's drag goes once, when it
 * ends.
 */
@Composable
internal fun SubtitleStyleEditor(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SubtitlePreview(style)

        StyleRow("Size", "${style.size.toInt()} px") {
            StyleSlider(style.size, 12.0..72.0, 1.0) { onChange(style.copy(size = it)) }
        }

        StyleRow("Colour") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SubtitleStyle.colors.forEach { name ->
                    val selected = style.color == name
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(subtitleColor(name))
                            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            .clickable { onChange(style.copy(color = name)) },
                    )
                }
            }
        }

        StyleRow("Font") {
            ChoiceRow(listOf("system" to "System", "rounded" to "Rounded", "serif" to "Serif", "mono" to "Mono"), style.font) {
                onChange(style.copy(font = it))
            }
        }

        StyleRow("Weight") {
            ChoiceRow(
                listOf("regular" to "Reg", "medium" to "Med", "semibold" to "Semi", "bold" to "Bold", "heavy" to "Heavy"),
                style.weight,
            ) { onChange(style.copy(weight = it)) }
        }

        StyleRow("Edge") {
            ChoiceRow(listOf("none" to "None", "shadow" to "Shadow", "outline" to "Outline"), style.edge) {
                onChange(style.copy(edge = it))
            }
        }

        StyleRow("Background", if (style.boxOpacity < 0.01) "Off" else "${(style.boxOpacity * 100).toInt()}%") {
            StyleSlider(style.boxOpacity, 0.0..0.9, 0.05) { onChange(style.copy(boxOpacity = it)) }
        }

        StyleRow("Distance from bottom", "${style.bottom.toInt()} px") {
            StyleSlider(style.bottom, 0.0..160.0, 2.0) { onChange(style.copy(bottom = it)) }
        }

        DevChip("Reset to defaults", false) { onChange(SubtitleStyle()) }
    }
}

/** The preview: a grey frame like a dark picture, with a line drawn the way the player draws it (size capped at 34). */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    Box(
        Modifier.fillMaxWidth().height(96.dp)
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
            PearText(title, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = Color.White)
            if (value != null) PearText(value, style = PearTheme.type.footnote, color = Color.White.copy(alpha = 0.6f))
        }
        control()
    }
}

/** One choice out of a few, as chips. Shared with the panel's own tabs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    // Wraps rather than squeezing a chip's label into a column of letters when the panel is narrow.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) -> DevChip(label, selected == value, compact = true) { onPick(value) } }
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

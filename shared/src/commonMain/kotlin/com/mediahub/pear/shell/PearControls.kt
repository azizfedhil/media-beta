package com.mediahub.pear.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.ui.semantics.Role
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.Accents

/** How a [PearCapsuleButton] reads: neutral (the fill), the accent (solid), or destructive (red text on the fill). */
enum class PearButtonStyle { Neutral, Accent, Destructive }

/**
 * A flat round button for an opaque surface: a `c.fill` disc that darkens to `c.fillStrong` on hover, and shrinks a little on
 * press. No glass, so it never sits in glass on glass.
 */
@Composable
fun PearCircleButton(onClick: () -> Unit, symbol: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, PearMotion.press<Float>())
    val fill by animateColorAsState(if (hovered) c.fillStrong else c.fill, PearMotion.snappy<Color>(PearMotion.FAST))
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(fill)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PearIcon(symbol, size = 16.dp, tint = c.textPrimary) }
}

/**
 * A flat capsule button with a label, optionally a leading symbol, for an opaque surface. [PearButtonStyle.Accent] is solid
 * accent with the theme's `onAccent` ink; the others sit on `c.fill`.
 */
@Composable
fun PearCapsuleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    style: PearButtonStyle = PearButtonStyle.Neutral,
) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, PearMotion.press<Float>())
    val target = when {
        style == PearButtonStyle.Accent -> c.accent
        hovered -> c.fillStrong
        else -> c.fill
    }
    val fill by animateColorAsState(target, PearMotion.snappy<Color>(PearMotion.FAST))
    val ink = when (style) {
        PearButtonStyle.Accent -> c.onAccent
        PearButtonStyle.Destructive -> c.red
        PearButtonStyle.Neutral -> c.textPrimary
    }
    Row(
        modifier.height(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50)).background(fill)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) PearIcon(symbol, size = 16.dp, tint = ink)
        PearText(label, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = ink, maxLines = 1)
    }
}

/** An iOS-style switch: a 40 x 24 dp track, in the accent when on, with a white thumb that slides across. */
@Composable
fun PearSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = PearTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.fill, PearMotion.snappy<Color>(PearMotion.FAST))
    val thumb by animateDpAsState(if (checked) 18.dp else 2.dp, PearMotion.snappy<Dp>(PearMotion.FAST))
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(width = 40.dp, height = 24.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(percent = 50)).background(track)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
    ) {
        Box(Modifier.offset(x = thumb, y = 2.dp).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * A settings row: a title (and an optional subtitle, both of which wrap rather than push the trailing control off), then a
 * trailing control. Tapping the row runs [onClick] when it is given, with a hover fill on desktop.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (hovered && onClick != null) c.fillSubtle else Color.Transparent,
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val interactive = if (onClick == null) Modifier else
        Modifier.hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).background(fill).then(interactive).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.Medium), maxLines = 2)
            subtitle?.let { PearText(it, style = PearTheme.type.caption, color = c.textSecondary) }
        }
        trailing()
    }
}

/**
 * An iOS inset-grouped container: a solid surface, with rows separated by [SettingsDivider]. An optional heading sits above
 * it and an optional footnote below. No border.
 */
@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PearTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { PearText(it, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = c.textSecondary, modifier = Modifier.padding(start = 16.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.lg)).background(c.surface).padding(vertical = 4.dp), content = content)
        footer?.let { PearText(it, style = PearTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

/** The hairline between two rows of a [SettingsGroup], inset 16 dp from the left. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(PearTheme.colors.hairline))
}

/** Two flat capsules, minus and plus, for a value that steps by a fixed amount. The value is shown by the row around it. */
@Composable
fun PearStepper(onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, minusLabel: String = "-5s", plusLabel: String = "+5s") {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PearCapsuleButton(label = minusLabel, onClick = onMinus)
        PearCapsuleButton(label = plusLabel, onClick = onPlus)
    }
}

/** A capsule of equal segments, with the selected one lifted on `c.fillStrong`. Labels stay on one line. */
@Composable
fun SegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            val bg by animateColorAsState(if (on) c.fillStrong else Color.Transparent, PearMotion.snappy<Color>(PearMotion.FAST))
            Box(
                Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(percent = 50)).background(bg)
                    .clickable { onSelect(i) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText(
                    label,
                    style = PearTheme.type.subheadline.weight(if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Items that wrap onto the next line instead of running past the edge: chips, swatches and symbols. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * Colour swatches in a grid that wraps. The selected one carries a check in its own contrast colour. Used where a colour is
 * picked from the accent presets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwatchGrid(colors: List<Color>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colors.forEachIndexed { i, col ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(col).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (i == selectedIndex) PearIcon("checkmark", size = 16.dp, tint = Accents.contrastingForeground(col))
            }
        }
    }
}

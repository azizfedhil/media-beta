package com.mediahub.pear.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.ui.text.font.FontWeight

/** Floating or docked bar (top bar, player controls). Pass `RoundedCornerShape(0.dp)` for an edge-to-edge bar. */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    contentPadding: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(modifier, shape) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = contentPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * Round icon button, or a capsule when [label] is set. [tint] is the colour wash (the Swift app uses the
 * accent at 55% for the active player buttons).
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    label: String? = null,
    size: Dp = 44.dp,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val type = PearTheme.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, PearMotion.snappy<Float>(PearMotion.FAST))
    val sizing = if (label == null) Modifier.size(size) else Modifier.height(size).widthIn(min = size)
    GlassSurface(
        modifier = modifier.then(sizing)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(percent = 50),
        wash = tint?.copy(alpha = 0.55f),
        highlight = hovered && enabled,
        sheen = if (tint == null) PearTheme.colors.button else null,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = if (label != null) 16.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (symbol != null) PearIcon(symbol, size = 20.dp)
            if (label != null) PearText(label, style = type.subheadline.weight(FontWeight.SemiBold))
        }
    }
}

/** One capsule holding a row of icon buttons (the player's option pill). */
@Composable
fun GlassPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    GlassSurface(modifier, RoundedCornerShape(percent = 50)) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A button inside a [GlassPill]: no glass of its own, a fill on hover and a filled accent when [selected]. */
@Composable
fun GlassPillButton(symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        selected -> c.accent.copy(alpha = 0.55f)
        hovered && enabled -> c.fill
        else -> Color.Transparent
    }
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(percent = 50)).background(bg)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PearIcon(symbol, size = 20.dp, tint = if (enabled) c.textPrimary else c.textDisabled) }
}

/** Content card. Clickable when [onClick] is given. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val interactive = if (onClick == null) Modifier else
        Modifier.hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = source, indication = null, onClick = onClick)
    GlassSurface(modifier.then(interactive), shape, wash = PearTheme.colors.panel, highlight = hovered && onClick != null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Dropdown / context menu panel. Place it where the menu should open; positioning is the caller's job. */
@Composable
fun GlassMenu(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.widthIn(min = 220.dp), RoundedCornerShape(PearRadii.lg), sheen = PearTheme.colors.menu) {
        Column(Modifier.padding(6.dp), content = content)
    }
}

@Composable
fun GlassMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, symbol: String? = null, destructive: Boolean = false) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color = if (destructive) c.red else c.textPrimary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.sm)).background(if (hovered) c.fill else Color.Transparent)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) PearIcon(symbol, size = 18.dp, tint = color)
        PearText(label, style = PearTheme.type.subheadline, color = color)
    }
}

@Composable
fun GlassMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp).height(1.dp).background(PearTheme.colors.outline))
}

/** Bottom sheet panel: rounded top corners and a grabber. */
@Composable
fun GlassSheet(modifier: Modifier = Modifier, floating: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = PearTheme.colors
    // A sheet rises from the bottom edge with a handle; a floating panel is a card with all corners rounded and no handle.
    val shape = if (floating) {
        RoundedCornerShape(PearRadii.sheet)
    } else {
        RoundedCornerShape(topStart = PearRadii.sheet, topEnd = PearRadii.sheet, bottomStart = 0.dp, bottomEnd = 0.dp)
    }
    GlassSurface(modifier, shape, sheen = c.sheen) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = if (floating) 20.dp else 10.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!floating) Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(percent = 50)).background(c.textTertiary))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

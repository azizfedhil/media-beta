package com.mediahub.pear.glass

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
 * Round icon button, or a capsule when [label] is set. [tint] is a wash on the glass (the accent at 55%, for an active
 * control, as the Swift app does). [iconTint] colours the symbol alone and leaves the glass neutral, as the watched check
 * does on iOS. A changed [symbol] cross-fades, the way iOS replaces symbols.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    label: String? = null,
    size: Dp = 44.dp,
    tint: Color? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
) {
    val type = PearTheme.type
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, PearMotion.press<Float>())
    val glyph by animateColorAsState(iconTint ?: c.textPrimary, PearMotion.snappy<Color>(PearMotion.FAST))
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
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = if (label != null) 16.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (symbol != null) {
                Crossfade(targetState = symbol, animationSpec = tween(PearMotion.BASE)) { name ->
                    PearIcon(name, size = 20.dp, tint = glyph)
                }
            }
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
    val target = when {
        selected -> c.accent.copy(alpha = 0.55f)
        hovered && enabled -> c.fill
        else -> Color.Transparent
    }
    val bg by animateColorAsState(target, PearMotion.snappy<Color>(PearMotion.FAST))
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(percent = 50)).background(bg)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PearIcon(symbol, size = 20.dp, tint = if (enabled) c.textPrimary else c.textDisabled) }
}

/** Content card: a solid surface with no contour, as on iOS. Clickable when [onClick] is given. */
@Composable
fun ContentCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val interactive = if (onClick == null) Modifier else
        Modifier.hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = source, indication = null, onClick = onClick)
    val c = PearTheme.colors
    // The card's face is the solid surface colour (#1C1C1E, the iOS dark cell colour), with no glass and no contour in any
    // mode. Hovering a clickable card lifts it a step, and the step fades in.
    val lift by animateFloatAsState(if (hovered && onClick != null) 1f else 0f, PearMotion.snappy<Float>(PearMotion.FAST))
    Box(modifier.then(interactive).clip(shape).background(c.surface)) {
        if (lift > 0f) Box(Modifier.matchParentSize().background(c.fillSubtle.copy(alpha = c.fillSubtle.alpha * lift)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Dropdown / context menu panel. Place it where the menu should open; positioning is the caller's job. */
@Composable
fun GlassMenu(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.widthIn(min = 220.dp), RoundedCornerShape(PearRadii.lg)) {
        Column(Modifier.padding(6.dp), content = content)
    }
}

@Composable
fun GlassMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, symbol: String? = null, destructive: Boolean = false) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color = if (destructive) c.red else c.textPrimary
    val fill by animateColorAsState(if (hovered) c.fill else Color.Transparent, PearMotion.snappy<Color>(PearMotion.FAST))
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.sm)).background(fill)
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

/** The grabber's hit area on a drag sheet: taller than its pill, so a finger can take hold of it. */
private val GrabberHitHeight = 24.dp
private val GrabberPillHeight = 5.dp

/** The top padding above a sheet's content. A drag sheet's taller grabber takes the room, and it never goes below 0. */
private fun sheetTopPadding(floating: Boolean, drag: SheetDragState?): Dp = when {
    floating -> 20.dp
    drag == null -> 10.dp
    else -> (10.dp - (GrabberHitHeight - GrabberPillHeight)).coerceAtLeast(0.dp)
}

/** A sheet's grabber. On a drag sheet it is a 24 dp strip that takes the drag, with the same pill inside. */
@Composable
private fun SheetGrabber(drag: SheetDragState?) {
    val pill = Modifier.size(width = 36.dp, height = GrabberPillHeight).clip(RoundedCornerShape(percent = 50)).background(PearTheme.colors.textTertiary)
    if (drag == null) {
        Box(pill)
    } else {
        Box(Modifier.fillMaxWidth().height(GrabberHitHeight).sheetHandleDrag(drag), contentAlignment = Alignment.Center) {
            Box(pill)
        }
    }
}

/**
 * A solid sheet with the same shape as [GlassSheet]: no glass and no blur. Used where a panel floats over a busy picture, so
 * the controls inside it don't sit in glass on glass. With [drag], the sheet moves with a drag and takes a pull to expand.
 */
@Composable
fun FlatSheet(modifier: Modifier = Modifier, floating: Boolean = false, drag: SheetDragState? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PearTheme.colors
    val shape = if (floating) {
        RoundedCornerShape(PearRadii.sheet)
    } else {
        RoundedCornerShape(topStart = PearRadii.sheet, topEnd = PearRadii.sheet, bottomStart = 0.dp, bottomEnd = 0.dp)
    }
    val outer = if (drag != null && !floating) modifier.sheetDragOffset(drag) else modifier
    Box(outer.clip(shape).background(c.surface)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = sheetTopPadding(floating, drag), bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!floating) SheetGrabber(drag)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/**
 * Bottom sheet panel: rounded top corners and a grabber. With [drag], the sheet moves with a drag, takes a pull to expand,
 * and closes when pulled away.
 */
@Composable
fun GlassSheet(modifier: Modifier = Modifier, floating: Boolean = false, drag: SheetDragState? = null, content: @Composable ColumnScope.() -> Unit) {
    // A sheet rises from the bottom edge with a handle; a floating panel is a card with all corners rounded and no handle.
    val shape = if (floating) {
        RoundedCornerShape(PearRadii.sheet)
    } else {
        RoundedCornerShape(topStart = PearRadii.sheet, topEnd = PearRadii.sheet, bottomStart = 0.dp, bottomEnd = 0.dp)
    }
    val outer = if (drag != null && !floating) modifier.sheetDragOffset(drag) else modifier
    GlassSurface(outer, shape) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = sheetTopPadding(floating, drag), bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!floating) SheetGrabber(drag)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** The old name of [ContentCard], kept for one release so existing callers still compile. */
@Deprecated("Cards are solid, not glass: use ContentCard", ReplaceWith("ContentCard(modifier, onClick, shape, content)"))
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    content: @Composable ColumnScope.() -> Unit,
) = ContentCard(modifier, onClick, shape, content)

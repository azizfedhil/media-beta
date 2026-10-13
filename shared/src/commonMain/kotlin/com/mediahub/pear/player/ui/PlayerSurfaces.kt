package com.mediahub.pear.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/**
 * How strong the player's tint edge is, against the theme's outline colour: half. The blur look draws no outline; the glass
 * styles draw their own edge through Haze.
 */
private const val PLAYER_OUTLINE = 0.5f

/**
 * The wash under the player's glass, tuned by eye against the iOS player (its Liquid Glass carries a light tint): the controls
 * are a light smoke (the grey pill and round buttons), the panels a dark grey (the sub-menus). Both are drawn with a rim, as
 * iOS draws its glass. The page cards, the sidebar and the bars keep `PearColors.panel`.
 */
private val PLAYER_CONTROL_WASH = Color.White.copy(alpha = 0.14f)
private val PLAYER_CARD_WASH = Color(0xFF2C2C2E).copy(alpha = 0.72f)

/** The look every overlay surface follows. Set once by [PlayerOverlay]. */
internal val LocalPlayerLook = compositionLocalOf { PlayerLook.Flat }

internal enum class SurfaceKind { Circle, Capsule, Card }

private val Pill = RoundedCornerShape(percent = 50)

/**
 * One overlay surface, drawn according to [LocalPlayerLook]:
 * blur (a Haze glass surface), tint (cheap, for moving video) or flat (glass switched off).
 * The flat fills are the ones PlayerView.swift uses when "Liquid Glass controls" is off
 * (circle 0.4 black or the tint at 0.75, capsule 0.55, card 0.88).
 * No surface takes a gradient: the glass shows through, as it does on iOS.
 */
@Composable
internal fun PlayerSurface(
    modifier: Modifier = Modifier,
    kind: SurfaceKind = SurfaceKind.Capsule,
    shape: RoundedCornerShape = if (kind == SurfaceKind.Card) RoundedCornerShape(26.dp) else Pill,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    when (LocalPlayerLook.current) {
        PlayerLook.Blur -> {
            // An active button keeps its accent tint; everything else takes the player's own light or dark wash, with a rim.
            val wash = tint?.copy(alpha = 0.55f) ?: if (kind == SurfaceKind.Card) PLAYER_CARD_WASH else PLAYER_CONTROL_WASH
            GlassSurface(modifier, shape, wash = wash, border = true, content = content)
        }
        PlayerLook.Tint -> {
            val base = Color.Black.copy(alpha = if (kind == SurfaceKind.Card) 0.55f else 0.38f)
            val fill = tint?.copy(alpha = 0.55f)?.compositeOver(base) ?: base
            Box(modifier.clip(shape).background(fill).border(1.dp, Color.White.copy(alpha = 0.14f * PLAYER_OUTLINE), shape), content = content)
        }
        PlayerLook.Flat -> {
            val fill = when (kind) {
                SurfaceKind.Circle -> tint?.copy(alpha = 0.75f) ?: Color.Black.copy(alpha = 0.4f)
                SurfaceKind.Capsule -> Color.Black.copy(alpha = 0.55f)
                SurfaceKind.Card -> Color.Black.copy(alpha = 0.88f)
            }
            Box(modifier.clip(shape).background(fill), content = content)
        }
    }
}

/** Round glass button (close, skip, play/pause): `circleButton` in PlayerView.swift. */
@Composable
internal fun PlayerCircleButton(
    symbol: String,
    size: Dp,
    icon: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, PearMotion.snappy<Float>(PearMotion.FAST))
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    PlayerSurface(
        modifier = modifier.size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .then(semantic)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        kind = SurfaceKind.Circle,
        tint = tint,
    ) {
        Box(
            Modifier.fillMaxSize().background(if (hovered && enabled) Color.White.copy(alpha = 0.12f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { PearIcon(symbol, size = icon, tint = Color.White) }
    }
}

/** A capsule holding the option buttons (the Swift "controls pill"). */
@Composable
internal fun PlayerPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    PlayerSurface(modifier, SurfaceKind.Capsule) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A button inside a [PlayerPill]: no surface of its own, a faint fill on hover and an accent disc when [selected]
 * (`pillButton` in PlayerView.swift: 46 pt slot, 19 pt icon). Give [label] instead of [symbol] for a text button.
 */
@Composable
internal fun PlayerPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    label: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        selected -> PearTheme.colors.accent.copy(alpha = 0.6f)
        hovered && enabled -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    val sizing = if (label == null) Modifier.size(46.dp) else Modifier.height(46.dp).widthIn(min = 46.dp)
    Box(
        modifier.then(sizing).then(semantic).clip(Pill).background(bg)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (symbol != null) PearIcon(symbol, size = 19.dp, tint = Color.White)
        if (label != null) {
            PearText(label, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = Color.White, modifier = Modifier.padding(horizontal = 14.dp), maxLines = 1)
        }
    }
}

/** Indeterminate ring, white. Used on the play button while loading and alone while buffering. */
@Composable
internal fun PlayerSpinner(modifier: Modifier = Modifier, diameter: Dp = 36.dp) {
    val transition = rememberInfiniteTransition(label = "player-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier.size(diameter)) {
        val stroke = 3.dp.toPx()
        drawArc(
            color = Color.White.copy(alpha = 0.92f),
            startAngle = angle,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

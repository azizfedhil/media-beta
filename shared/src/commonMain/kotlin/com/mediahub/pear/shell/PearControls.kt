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

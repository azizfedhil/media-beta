package com.mediahub.pear.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.player.SubtitleStyle
import com.mediahub.pear.theme.PearText

// Subtitle drawing (Batch 4.4c). Spec: `SubtitleOverlay` and its `label` in SubtitleSupport.swift. The player and the
// settings preview both draw through [SubtitleLine], so the preview is the player's own drawing, not an imitation.

/** The outline's four copies, each 1.3 px away (iOS: the four `shadow(radius: 0)` offsets). */
private val OUTLINE_OFFSETS = listOf(1.3f to 0f, -1.3f to 0f, 0f to 1.3f, 0f to -1.3f)

/** The subtitle colour for a style's name. Unknown names are white, as on iOS. */
internal fun subtitleColor(name: String): Color = when (name) {
    "yellow" -> Color(1f, 0.9f, 0.2f)
    "cyan" -> Color(0.3f, 0.9f, 1f)
    "green" -> Color(0.4f, 1f, 0.5f)
    "orange" -> Color(1f, 0.65f, 0.2f)
    "pink" -> Color(1f, 0.55f, 0.75f)
    else -> Color.White
}

/**
 * The text style of a subtitle. Compose has no rounded family, so "rounded" uses the sans-serif family; the rest map
 * directly. Weights follow the iOS names.
 */
internal fun subtitleTextStyle(style: SubtitleStyle): TextStyle = TextStyle(
    fontFamily = when (style.font) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    },
    fontWeight = when (style.weight) {
        "regular" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "bold" -> FontWeight.Bold
        "heavy" -> FontWeight.ExtraBold
        else -> FontWeight.SemiBold
    },
    fontSize = style.size.toFloat().sp,
    lineHeight = (style.size * 1.2).toFloat().sp,
    textAlign = TextAlign.Center,
)

/**
 * One subtitle line, drawn the way iOS draws it: the text in the chosen colour, the chosen edge (none, a soft shadow, or
 * an outline), and the box behind it when the box opacity is set.
 */
@Composable
internal fun SubtitleLine(text: String, style: SubtitleStyle, modifier: Modifier = Modifier) {
    val boxed = style.boxOpacity > 0.01
    val base = subtitleTextStyle(style)
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = style.boxOpacity.toFloat()))
            .padding(horizontal = if (boxed) 12.dp else 0.dp, vertical = if (boxed) 5.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (style.edge == "outline") {
            OUTLINE_OFFSETS.forEach { (dx, dy) ->
                PearText(
                    text,
                    modifier = Modifier.offset(dx.dp, dy.dp),
                    style = base,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val fillStyle = if (style.edge == "shadow") base.copy(shadow = Shadow(Color.Black, Offset.Zero, 4f)) else base
        PearText(text, style = fillStyle, color = subtitleColor(style.color), textAlign = TextAlign.Center)
    }
}

/**
 * The subtitle line over the picture: at the bottom, [SubtitleStyle.bottom] above the edge, and lifted above the controls
 * while they show. It sits above the video and under the controls and panels, and takes no input, so clicks reach the
 * picture beneath.
 */
@Composable
internal fun SubtitleLayer(text: String?, style: SubtitleStyle, controlsShown: Boolean) {
    if (text.isNullOrBlank()) return
    // Controls lift the line above them (iOS: `lift: showControls ? 112 : 0`); here the lift is the controls' real height.
    val lift = if (controlsShown) LocalPlayerMargins.current.panelClearance else 0.dp
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(bottom = style.bottom.toFloat().dp + lift),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // At most 90% of the window wide, as on iOS, so a long line wraps rather than running off the edge.
        SubtitleLine(text, style, Modifier.widthIn(max = maxWidth * 0.9f))
    }
}

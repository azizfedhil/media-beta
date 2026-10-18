package com.mediahub.pear.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.model.Video
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.openUrl
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlin.math.abs
import androidx.compose.animation.core.animateFloatAsState
import com.mediahub.pear.theme.PearMotion

/**
 * Episode-card styling without the description: the thumbnail dissolves into a softly tinted panel,
 * with the kind ("TRAILER"), the title, and a play button. Shorter than EpisodeCard (250 × 214 dp).
 */
@Composable
fun TrailerCard(
    video: Video,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardWidth = 250.dp
    val cardHeight = 214.dp
    val thumbnailHeight = cardWidth * 9f / 16f // ~140.6 dp
    val radius = PearRadii.card // 22 dp

    // Atmospheric tint derived from the thumbnail
    val atmosphericTint = remember(video.key, video.name) {
        val hue = (abs(video.key.hashCode()) % 360).toFloat()
        Color.hsv(hue, 0.45f, 0.35f)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (isHovered) 1.02f else 1.0f, PearMotion.press<Float>())

    val handleClick = onClick ?: { openUrl(video.watchUrl) }

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clip(RoundedCornerShape(radius))
            .background(Color(0xFF171717)) // iOS: Color(white: 0.09)
            .clickable(interactionSource = interactionSource, indication = null, onClick = handleClick),
    ) {
        // Atmospheric tint layer
        Box(
            Modifier.fillMaxSize()
                .background(atmosphericTint.copy(alpha = 0.30f)),
        )

        // Dissolving video thumbnail across top portion
        Box(
            Modifier
                .fillMaxWidth()
                .height(thumbnailHeight)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Linear gradient mask (black 0 -> black 0.5 -> transparent 1.0)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Black,
                            0.50f to Color.Black,
                            1.0f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            RemoteImage(
                url = video.thumbnailUrl,
                maxEdge = 1000,
                modifier = Modifier.fillMaxSize(),
            )

            // Centered circular play button overlay with y = -12.dp offset
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-12).dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .drawWithContent {
                        drawContent()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.25f),
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 0.5.dp.toPx()),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                PearIcon(
                    symbol = "play.fill",
                    size = 16.dp,
                    tint = Color.White,
                )
            }
        }

        // 1.dp outer stroke border
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radius))
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.10f),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )

        // Bottom-start Text overlay: uppercase kind + video title
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PearText(
                text = video.type.uppercase(),
                style = PearTheme.type.caption2.weight(FontWeight.SemiBold).copy(
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                ),
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
            )

            PearText(
                text = video.name,
                style = PearTheme.type.headline.weight(FontWeight.Bold).copy(
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                ),
                color = Color.White,
                maxLines = 2,
            )
        }
    }
}
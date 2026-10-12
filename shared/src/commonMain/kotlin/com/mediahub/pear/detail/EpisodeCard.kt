package com.mediahub.pear.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun EpisodeCard(
    ep: EpisodeItem,
    isWatched: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = 240.dp
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    val scale = if (hovered) 1.02f else 1f

    Column(
        modifier = modifier.width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.card))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(ep.image, Modifier.fillMaxSize())

            // Episode number tag
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.xs))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                PearText("E${ep.id}", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
            }

            // Watched indicator badge
            if (isWatched) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(PearRadii.pill))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PearIcon("checkmark.circle.fill", size = 12.dp, tint = PearTheme.colors.accent)
                        PearText("Watched", style = PearTheme.type.caption2, color = Color.White)
                    }
                }
            }

            // Rating badge top trailing
            ep.rating?.let { r ->
                if (r > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .clip(RoundedCornerShape(PearRadii.xs))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        PearText("★ ${(kotlin.math.round(r * 10) / 10)}", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PearText(
                text = "${ep.id}. ${ep.name}",
                style = PearTheme.type.subheadline.weight(if (isSelected) FontWeight.Bold else FontWeight.SemiBold),
                color = if (isSelected) PearTheme.colors.accent else PearTheme.colors.textPrimary,
                maxLines = 1,
            )

            val metaLine = listOfNotNull(
                ep.runtime?.let { "${it} min" },
                ep.airDate,
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                PearText(metaLine, style = PearTheme.type.caption2, color = PearTheme.colors.textSecondary, maxLines = 1)
            }

            ep.overview?.takeIf { it.isNotEmpty() }?.let { o ->
                PearText(o, style = PearTheme.type.caption, color = PearTheme.colors.textTertiary, maxLines = 2)
            }
        }
    }
}
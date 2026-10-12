package com.mediahub.pear.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.mediahub.pear.model.Video
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.openUrl
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun TrailerCard(video: Video, modifier: Modifier = Modifier) {
    val width = 230.dp
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    val scale = if (hovered) 1.02f else 1f

    Column(
        modifier = modifier.width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null) {
                openUrl(video.watchUrl)
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.card))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(video.thumbnailUrl, Modifier.fillMaxSize())

            // Play button circle in center
            Box(
                Modifier.align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearIcon("play.fill", size = 18.dp, tint = Color.White)
            }

            // Tag top leading
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                PearText(video.type.uppercase(), style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
            }
        }

        PearText(
            text = video.name,
            style = PearTheme.type.footnote.weight(FontWeight.SemiBold),
            maxLines = 2,
        )
    }
}
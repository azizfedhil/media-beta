package com.mediahub.pear.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.meta.ThemeRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.ThemedTitle
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ThemeCarousel(
    row: ThemeRow,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (row.items.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PearText(
            text = row.title,
            style = PearTheme.type.title2.weight(FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(row.items) { item ->
                ThemeCard(item = item, onClick = { onSelectTitle(item.item) })
            }
        }
    }
}

/** 4:5 editorial card. */
@Composable
private fun ThemeCard(item: ThemedTitle, onClick: () -> Unit) {
    val width = 240.dp
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    val scale = if (hovered) 1.02f else 1f

    Box(
        Modifier.width(width).aspectRatio(4f / 5f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(PearRadii.card))
            .background(PearTheme.colors.fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        RemoteImage(item.item.heroUrl(false) ?: item.item.backdropUrl ?: item.item.posterUrl, Modifier.fillMaxSize())

        // Bottom gradient scrim
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.4f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item.item.year?.let { y ->
                PearText(y.toString(), style = PearTheme.type.caption2.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.8f))
            }
            PearText(item.item.name, style = PearTheme.type.title3.weight(FontWeight.Bold), color = Color.White, maxLines = 2)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PearIcon(if (item.item.type == "series") "tv" else "film", size = 12.dp, tint = Color.White)
                val caption = (listOf(item.item.typeLabel) + item.genres).joinToString(" · ")
                PearText(caption, style = PearTheme.type.caption, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
    }
}
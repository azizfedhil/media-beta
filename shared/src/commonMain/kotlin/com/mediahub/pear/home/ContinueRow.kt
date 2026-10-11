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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.UpNextItem
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

private sealed class ContinueCardItem(val updated: Long) {
    class InProgress(val entry: HistoryEntry) : ContinueCardItem(entry.updated)
    class Next(val item: UpNextItem) : ContinueCardItem(item.updated)
}

/**
 * Continue Watching carousel (spec: `ContinueRow`).
 * Shows in-progress playback cards and Up Next cards sorted by recency. Cards do NOT have a play button.
 */
@Composable
fun ContinueRow(
    entries: List<HistoryEntry>,
    upNext: List<UpNextItem>,
    onSelectTitle: (MetaPreview, Int?, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(entries, upNext) {
        (entries.map { ContinueCardItem.InProgress(it) } + upNext.map { ContinueCardItem.Next(it) })
            .sortedByDescending { it.updated }
    }
    if (items.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PearIcon("play.circle.fill", size = 18.dp, tint = PearTheme.colors.accent2)
            PearText("Continue Watching", style = PearTheme.type.title3.weight(FontWeight.Bold))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items) { card ->
                when (card) {
                    is ContinueCardItem.InProgress -> {
                        val e = card.entry
                        ContinueCard(
                            entry = e,
                            onClick = { onSelectTitle(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode) },
                        )
                    }
                    is ContinueCardItem.Next -> {
                        val n = card.item
                        UpNextCard(
                            item = n,
                            onClick = { onSelectTitle(n.item, n.season, n.episode) },
                        )
                    }
                }
            }
        }
    }
}

/** 16:9 continue card without play button. */
@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit) {
    val width = 270.dp
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val scale = if (hovered) 1.02f else 1f

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(entry.thumb ?: entry.item.backdropUrl ?: entry.item.posterUrl, Modifier.fillMaxSize())

            // Clock pill at top-trailing
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.xs))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                PearText(Fmt.clock(entry.position), style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
            }

            // Bottom progress bar
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(entry.progress.toFloat())
                        .background(PearTheme.colors.accent),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PearText(entry.item.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), maxLines = 1)
            val left = Fmt.remaining(entry.duration - entry.position)
            val subtitle = entry.seasonEpisode?.let { "S${it.season} · E${it.episode} · $left" } ?: left
            PearText(subtitle, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun UpNextCard(item: UpNextItem, onClick: () -> Unit) {
    val width = 270.dp
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val scale = if (hovered) 1.02f else 1f

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(item.thumb ?: item.item.backdropUrl ?: item.item.posterUrl, Modifier.fillMaxSize())

            // UP NEXT pill at top-leading
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(PearTheme.colors.accent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                PearText("UP NEXT", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = PearTheme.colors.onAccent)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PearText(item.item.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), maxLines = 1)
            PearText("S${item.season} · E${item.episode} · ${item.title}", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 1)
        }
    }
}
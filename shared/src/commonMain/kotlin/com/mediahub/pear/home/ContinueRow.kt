package com.mediahub.pear.home

import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.detail.openSourceChooser
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.mediahub.pear.shell.DragLazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.UpNextItem
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.upgradeTmdbStill
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

    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    // Wide layout: the cards are a third bigger. The phone layout keeps 1x.
    val wideScale = if (LocalWideLayout.current) WideScale.CARD else 1f
    // A card opens the source chooser for its title or episode. Only a title with no IMDb id falls back to the details page.
    fun choose(item: MetaPreview, season: Int?, episode: Int?, title: String?, thumb: String?) {
        scope.launch {
            if (!openSourceChooser(env, item, season, episode, title, thumb)) onSelectTitle(item, season, episode)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Purple play icon from mockup
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF9D63E8)), contentAlignment = Alignment.Center) {
                PearIcon("play.fill", size = 11.dp, tint = Color.White)
            }
            PearText("Continue Watching", style = PearTheme.type.title3.weight(FontWeight.Bold))
        }

        DragLazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items) { card ->
                when (card) {
                    is ContinueCardItem.InProgress -> {
                        val e = card.entry
                        ContinueCard(
                            entry = e,
                            scale = wideScale,
                            onClick = { choose(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode, e.episodeTitle, e.thumb) },
                        )
                    }
                    is ContinueCardItem.Next -> {
                        val n = card.item
                        UpNextCard(
                            item = n,
                            scale = wideScale,
                            onClick = { choose(n.item, n.season, n.episode, n.title, n.thumb) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit, scale: Float = 1f) {
    val width = (260f * scale).dp
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift = if (hovered) 1.02f else 1f
    // The text follows the layout, not the hover lift: 20% larger in the wide layout.
    val text = if (LocalWideLayout.current) WideScale.CONTINUE_TEXT else 1f

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(upgradeTmdbStill(entry.thumb ?: entry.item.backdropUrl ?: entry.item.posterUrl), Modifier.fillMaxSize())

            // UP NEXT badge on top-left (from mockup)
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(Color(0xFF8B6EF5).copy(alpha = 0.85f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                PearText("UP NEXT", style = PearTheme.type.caption2.weight(FontWeight.Black).copy(fontSize = 8.sp), color = Color.White)
            }


            // Bottom progress bar
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(entry.progress.toFloat().coerceAtLeast(0.05f))
                        .background(Color(0xFF8B6EF5)),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            PearText(entry.item.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold).scaledBy(text), maxLines = 1)
            val sub = entry.seasonEpisode?.let { "S${it.season} · E${it.episode}" + (entry.episodeTitle?.let { t -> " · $t" } ?: "") }
                ?: Fmt.remaining(entry.duration - entry.position)
            PearText(sub, style = PearTheme.type.caption.scaledBy(text), color = Color.White.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}

@Composable
private fun UpNextCard(item: UpNextItem, onClick: () -> Unit, scale: Float = 1f) {
    val width = (260f * scale).dp
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift = if (hovered) 1.02f else 1f
    val text = if (LocalWideLayout.current) WideScale.CONTINUE_TEXT else 1f

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(upgradeTmdbStill(item.thumb ?: item.item.backdropUrl ?: item.item.posterUrl), Modifier.fillMaxSize())

            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(Color(0xFF8B6EF5).copy(alpha = 0.85f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                PearText("UP NEXT", style = PearTheme.type.caption2.weight(FontWeight.Black).copy(fontSize = 8.sp), color = Color.White)
            }

        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            PearText(item.item.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold).scaledBy(text), maxLines = 1)
            PearText("S${item.season} · E${item.episode} · ${item.title}", style = PearTheme.type.caption.scaledBy(text), color = Color.White.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}
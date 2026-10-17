package com.mediahub.pear.home

import androidx.compose.ui.geometry.Rect
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.titleMenuItems
import com.mediahub.pear.shell.touchStyle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.SE
import com.mediahub.pear.history.UpNextItem
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.upgradeTmdbStill
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.core.animateFloatAsState
import com.mediahub.pear.theme.PearMotion

private sealed class ContinueCardItem(val updated: Long) {
    class InProgress(val entry: HistoryEntry) : ContinueCardItem(entry.updated)
    class Next(val item: UpNextItem) : ContinueCardItem(item.updated)
}

/** Card width in the phone layout (iOS `ContinueCard.width`). The wide layout scales it. */
private const val CARD_WIDTH_DP = 270f

/** Space between cards, and the row's margin from the screen edge (iOS `LazyHStack(spacing: 14)`, `contentMargins(16)`). */
private val CARD_SPACING = 14.dp
private val ROW_MARGIN = 16.dp

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
    // Touch rules on a touch screen, desktop rules when a mouse is in use on a wide layout (see touchStyle).
    val touch = touchStyle()
    // A card opens the source chooser for its title or episode. Only a title with no IMDb id falls back to the details page.
    fun choose(item: MetaPreview, season: Int?, episode: Int?, title: String?, thumb: String?) {
        scope.launch {
            if (!openSourceChooser(env, item, season, episode, title, thumb)) onSelectTitle(item, season, episode)
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(horizontal = ROW_MARGIN),
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
            contentPadding = PaddingValues(horizontal = ROW_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(CARD_SPACING),
        ) {
            items(items) { card ->
                when (card) {
                    is ContinueCardItem.InProgress -> {
                        val e = card.entry
                        ContinueCard(
                            entry = e,
                            scale = wideScale,
                            onMenu = { rect ->
                                env.shell.openContextMenu(
                                    ContextMenuRequest(
                                        rect,
                                        continueMenuItems(
                                            env,
                                            e.item,
                                            remove = {
                                                env.data.history.remove(e.item.id)
                                                // A show with a finished episode would otherwise come back as an Up Next card.
                                                env.data.history.dismissUpNext(e.item.id)
                                            },
                                            onDetails = { onSelectTitle(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode) },
                                        ),
                                    ),
                                )
                            },
                            onClick = {
                                // Touch opens the title's page on this episode, as iOS does. Desktop keeps the source chooser.
                                if (touch) onSelectTitle(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode)
                                else choose(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode, e.episodeTitle, e.thumb)
                            },
                        )
                    }
                    is ContinueCardItem.Next -> {
                        val n = card.item
                        UpNextCard(
                            item = n,
                            scale = wideScale,
                            onMenu = { rect ->
                                env.shell.openContextMenu(
                                    ContextMenuRequest(
                                        rect,
                                        continueMenuItems(
                                            env,
                                            n.item,
                                            remove = { env.data.history.dismissUpNext(n.item.id) },
                                            onDetails = { onSelectTitle(n.item, n.season, n.episode) },
                                        ),
                                    ),
                                )
                            },
                            onClick = {
                                if (touch) onSelectTitle(n.item, n.season, n.episode)
                                else choose(n.item, n.season, n.episode, n.title, n.thumb)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A title in progress (iOS `ContinueCard`): the still with a bottom shade, the time reached as a pill, a progress bar, and
 * the time left. It has no "Up next" badge: that is for the next episode only.
 */
@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit, onMenu: (Rect) -> Unit, scale: Float = 1f) {
    val still = rememberEpisodeStill(entry.item, entry.seasonEpisode, entry.thumb)
    val width = (CARD_WIDTH_DP * scale).dp
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1.02f else 1f, PearMotion.press<Float>())
    // The text follows the layout, not the hover lift: 20% larger in the wide layout.
    val text = if (LocalWideLayout.current) WideScale.CONTINUE_TEXT else 1f
    val left = Fmt.remaining(entry.duration - entry.position)
    val sub = entry.seasonEpisode?.let { "S${it.season} · E${it.episode} · $left" } ?: left

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickOrMenu(source, onClick = onClick, onMenu = { onMenu(anchor.rect()) }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(upgradeTmdbStill(still ?: entry.item.backdropUrl ?: entry.item.posterUrl), Modifier.fillMaxSize())

            // Clear from the middle of the still to black at half strength at the bottom, so the bar and the pill read.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f)),
                ),
            )

            // Where playback stopped, top right (iOS: Fmt.clock of the position).
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                PearText(
                    Fmt.clock(entry.position),
                    style = PearTheme.type.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum").weight(FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                )
            }

            // Progress along the bottom edge: 4 dp, the track white at 30%, the fill the accent gradient.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(entry.progress.toFloat().coerceIn(0f, 1f))
                        .background(PearTheme.colors.gradient),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            PearText(entry.item.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold).scaledBy(text), maxLines = 1)
            PearText(sub, style = PearTheme.type.caption.scaledBy(text), color = Color.White.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}

@Composable
private fun UpNextCard(item: UpNextItem, onClick: () -> Unit, onMenu: (Rect) -> Unit, scale: Float = 1f) {
    val still = rememberEpisodeStill(item.item, SE(item.season, item.episode), item.thumb)
    val width = (CARD_WIDTH_DP * scale).dp
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1.02f else 1f, PearMotion.press<Float>())
    val text = if (LocalWideLayout.current) WideScale.CONTINUE_TEXT else 1f

    Column(
        Modifier.width(width)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickOrMenu(source, onClick = onClick, onMenu = { onMenu(anchor.rect()) }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PearRadii.md))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(upgradeTmdbStill(still ?: item.item.backdropUrl ?: item.item.posterUrl), Modifier.fillMaxSize())

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
/**
 * A Continue Watching card's menu (HomeView.swift): take the card off the row, then the title's own menu, as a poster has.
 */
private fun continueMenuItems(
    env: AppEnv,
    item: MetaPreview,
    remove: () -> Unit,
    onDetails: () -> Unit,
): List<ContextMenuItem> =
    listOf(ContextMenuItem("Remove from Continue Watching", "xmark.circle", destructive = true) { remove() }) +
        titleMenuItems(env, item, onDetails)

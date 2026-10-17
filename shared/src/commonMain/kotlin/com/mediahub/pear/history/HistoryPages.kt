package com.mediahub.pear.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.detail.openSourceChooser
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.home.PosterCard
import com.mediahub.pear.home.scaledBy
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.upgradeTmdbStill
import com.mediahub.pear.home.rememberEpisodeStill
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.attempt
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.titleMenuItems
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

/**
 * Continue Watching as a page, opened from the sidebar. The cards are Home's row's (titles in progress, and Up Next),
 * in a grid, the most recently watched first.
 */
@Composable
fun ContinueWatchingScreen(
    onBack: () -> Unit,
    onSelectTitle: (MetaPreview, Int?, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val history = env.data.history
    val scope = rememberCoroutineScope()
    val wide = LocalWideLayout.current
    val bottomInset = LocalBottomInset.current
    // Wide layout: cards a third bigger and text 20% larger, as on Home. The phone layout keeps 1x.
    val cardWidth = (260f * (if (wide) WideScale.CARD else 1f)).dp
    val text = if (wide) WideScale.CONTINUE_TEXT else 1f

    val inProgress = history.continueEntries
    val finished = history.finishedEntries

    // Up Next comes from the shows whose last episode is done. Keyed on those entries rather than their ids: finishing
    // the next episode of a show keeps its id but moves its watched marker, and its Up Next has to resolve again.
    var upNext by remember { mutableStateOf<List<UpNextItem>>(emptyList()) }
    var resolved by remember { mutableStateOf(false) }
    LaunchedEffect(finished) {
        upNext = resolveUpNext(env, finished)
        resolved = true
    }
    // A card dismissed a moment ago leaves at once, without waiting for the list to resolve again.
    val finishedIds = finished.map { it.id }.toSet()
    val nextCards = upNext.filter { it.item.id in finishedIds }

    val cards = (inProgress.map { ContinueShelf.InProgress(it) } + nextCards.map { ContinueShelf.Next(it) })
        .sortedByDescending { it.updated }

    // A card opens the source chooser for its title or episode. Only a title with no IMDb id falls back to its page.
    fun choose(item: MetaPreview, season: Int?, episode: Int?, title: String?, thumb: String?) {
        scope.launch {
            if (!openSourceChooser(env, item, season, episode, title, thumb)) onSelectTitle(item, season, episode)
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            cards.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = cardWidth),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp, bottom = bottomInset + 32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.key }) { card ->
                    when (card) {
                        is ContinueShelf.InProgress -> {
                            val e = card.entry
                            ContinueCard(
                                entry = e,
                                text = text,
                                onClick = {
                                    choose(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode, e.episodeTitle, e.thumb)
                                },
                                onMenu = { rect ->
                                    env.shell.openContextMenu(
                                        ContextMenuRequest(
                                            rect,
                                            continueWatchingMenu(
                                                env,
                                                e.item,
                                                remove = { env.data.history.remove(e.item.id) },
                                                onDetails = { onSelectTitle(e.item, e.seasonEpisode?.season, e.seasonEpisode?.episode) },
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                        is ContinueShelf.Next -> {
                            val n = card.item
                            UpNextCard(
                                item = n,
                                text = text,
                                onClick = { choose(n.item, n.season, n.episode, n.title, n.thumb) },
                                onMenu = { rect ->
                                    env.shell.openContextMenu(
                                        ContextMenuRequest(
                                            rect,
                                            continueWatchingMenu(
                                                env,
                                                n.item,
                                                remove = { env.data.history.dismissUpNext(n.item.id) },
                                                onDetails = { onSelectTitle(n.item, n.season, n.episode) },
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            resolved -> HistoryEmptyState(
                symbol = "play.circle",
                title = "Nothing to continue yet.",
                detail = "Titles you start watching show up here.",
            )
        }

        GlassTopBar(
            title = "Continue Watching",
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            onBack = onBack,
        )
    }
}

/**
 * Recently Watched as a page, opened from the sidebar: the titles watched as a whole (finished movies, and shows with a
 * watched marker), newest first. A title that Continue Watching is offering, or has in progress, is left to that page.
 */
@Composable
fun RecentlyWatchedScreen(
    onBack: () -> Unit,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val history = env.data.history
    val wide = LocalWideLayout.current
    val bottomInset = LocalBottomInset.current

    // Read here so the page follows both lists, and a switch of profile, which reloads them.
    val entries = history.entries
    val archive = history.archive
    val watched = remember(entries, archive) { history.allEntries.filter { it.isTitleWatched } }

    // The titles Continue Watching offers for their next episode are left out, so each title is on one of the two pages.
    // The ids come from the same resolve as that page, and are null until it has run.
    val finished = history.finishedEntries
    var upNextIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(finished) {
        upNextIds = resolveUpNext(env, finished).map { it.item.id }.toSet()
    }
    val shown = upNextIds?.let { offered ->
        watched.filter { !it.isInProgress && it.id !in offered }.sortedByDescending { it.updated }
    }

    Box(modifier.fillMaxSize()) {
        when {
            shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PearText("Loading…", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
            }
            shown.isEmpty() -> HistoryEmptyState(
                symbol = "clock",
                title = "Nothing watched yet.",
                detail = "Titles you finish show up here.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (wide) 165.dp else 110.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp, bottom = bottomInset + 32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { entry ->
                    PosterCard(
                        item = entry.item,
                        width = null,
                        showsTitle = true,
                        onClick = { onSelectTitle(entry.item) },
                    )
                }
            }
        }

        GlassTopBar(
            title = "Recently Watched",
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            onBack = onBack,
        )
    }
}

/**
 * Up Next for [entries]: the episode after each show's watched marker, as Home's Continue Watching row has it. This is
 * the logic of `HomeModel.loadUpNext`, which is private, so it is repeated here rather than changing Home.
 */
internal suspend fun resolveUpNext(env: AppEnv, entries: List<HistoryEntry>): List<UpNextItem> {
    val resolved = mutableListOf<UpNextItem>()
    for (entry in entries) {
        val next = attempt {
            UpNext.resolve(
                entry = entry,
                load = { season ->
                    env.data.episodes.load(entry.id, "series", season) { if (entry.id.startsWith("tt")) entry.id else null }
                },
            )
        }
        if (next != null) resolved.add(next)
    }
    return resolved
}

/** One card on Continue Watching: a title in progress, or the next episode of a finished show. */
private sealed class ContinueShelf(val updated: Long) {
    abstract val key: String

    class InProgress(val entry: HistoryEntry) : ContinueShelf(entry.updated) {
        override val key: String get() = "watch:${entry.id}"
    }

    class Next(val item: UpNextItem) : ContinueShelf(item.updated) {
        override val key: String get() = "next:${item.id}"
    }
}

/** A Continue Watching card's menu: take the card off the page, then the title's own menu, as a poster has. */
private fun continueWatchingMenu(
    env: AppEnv,
    item: MetaPreview,
    remove: () -> Unit,
    onDetails: () -> Unit,
): List<ContextMenuItem> =
    listOf(ContextMenuItem("Remove from Continue Watching", "xmark.circle", destructive = true) { remove() }) +
        titleMenuItems(env, item, onDetails)

@Composable
private fun ContinueCard(
    entry: HistoryEntry,
    text: Float,
    onClick: () -> Unit,
    onMenu: (Rect) -> Unit,
) {
    val still = rememberEpisodeStill(entry.item, entry.seasonEpisode, entry.thumb)
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1.02f else 1f, PearMotion.press<Float>())

    Column(
        Modifier.fillMaxWidth()
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
private fun UpNextCard(
    item: UpNextItem,
    text: Float,
    onClick: () -> Unit,
    onMenu: (Rect) -> Unit,
) {
    val still = rememberEpisodeStill(item.item, SE(item.season, item.episode), item.thumb)
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1.02f else 1f, PearMotion.press<Float>())

    Column(
        Modifier.fillMaxWidth()
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

            // UP NEXT badge, top-left, as on Home
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
            PearText(
                "S${item.season} · E${item.episode} · ${item.title}",
                style = PearTheme.type.caption.scaledBy(text),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HistoryEmptyState(symbol: String, title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PearIcon(symbol, size = 48.dp, tint = PearTheme.colors.textTertiary)
            PearText(title, style = PearTheme.type.headline)
            PearText(detail, style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
        }
    }
}

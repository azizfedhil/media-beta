package com.mediahub.pear.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.history.UpNext
import com.mediahub.pear.history.addToActiveLibrary
import com.mediahub.pear.history.isInActiveLibrary
import com.mediahub.pear.history.removeFromActiveLibrary
import com.mediahub.pear.home.CatalogRowView
import com.mediahub.pear.home.heroAmbientFor
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.SeasonInfo
import com.mediahub.pear.model.TmdbDetails
import com.mediahub.pear.model.Video
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.attempt
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.DragLazyGrid
import com.mediahub.pear.shell.DragLazyRow
import com.mediahub.pear.shell.FadedPoster
import com.mediahub.pear.shell.FeedCollapse
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalFeedCollapse
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.SourcePickerRequest
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlin.math.max
import kotlinx.coroutines.launch

/** The phone hero's height: iOS's 500 pt, less the status bar, which the app pads at the root (MainActivity). */
private val PhoneDetailHeroHeight = 440.dp

/**
 * The fade into the page at the bottom of the phone hero: eleven stops on a smoothstep curve, so the edge has no kink
 * where the art meets the page (iOS `heroFadeStops`).
 */
private fun phoneDetailFade(background: Color): Brush = Brush.verticalGradient(
    *Array(11) { i ->
        val t = i / 10f
        t to background.copy(alpha = t * t * (3 - 2 * t))
    },
)

@Composable
fun DetailScreen(
    item: MetaPreview,
    startSeason: Int? = null,
    startEpisode: Int? = null,
    onBack: () -> Unit,
    onSelectTitle: (MetaPreview) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val isWide = LocalWideLayout.current
    val isSeries = item.type == "series"

    var details by remember { mutableStateOf<TmdbDetails?>(null) }
    var similar by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var ratings by remember { mutableStateOf<List<MdbRating>>(emptyList()) }
    var trailers by remember { mutableStateOf<List<Video>>(emptyList()) }
    var logoUrl by remember { mutableStateOf<String?>(null) }
    var imdbId by remember { mutableStateOf(if (item.id.startsWith("tt")) item.id else "") }

    var selectedSeason by remember { mutableIntStateOf(startSeason ?: 1) }
    var selectedEpisode by remember { mutableIntStateOf(startEpisode ?: 1) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    // Set once the person picks a season or an episode, so the page's own landing choice never overrides it.
    var userChose by remember { mutableStateOf(false) }
    // The one-row episode list's scroll state: it brings the selected episode into view when a season arrives.
    val episodeListState = rememberLazyListState()

    val isWatched = env.data.history.entry(item.id)?.isTitleWatched == true
    // Read from whichever library the profile uses: Simkl's lists, or this device's.
    val isInLibrary = env.data.isInActiveLibrary(item.id, imdbId.ifEmpty { null })
    // The synopsis is the same text on both layouts.
    val synopsis = details?.overview ?: item.description
    val scope = rememberCoroutineScope()

    // Saves or drops the title in the active library, for both layouts.
    fun toggleLibrary() {
        scope.launch {
            if (isInLibrary) env.data.removeFromActiveLibrary(item, imdbId.ifEmpty { null })
            else env.data.addToActiveLibrary(item, imdbId.ifEmpty { null })
        }
    }

    // Ambient tone for both layouts
    val http = LocalHttp.current
    val artUrl = item.largePosterUrl() ?: item.backdropUrl ?: item.posterUrl
    LaunchedEffect(artUrl) {
        val colour = artUrl?.let { heroAmbientFor(http, it) } ?: return@LaunchedEffect
        env.shell.heroAmbientColor = colour
        env.shell.heroAmbientKnown = true
    }
    DisposableEffect(item.id) {
        onDispose {
            env.shell.heroAmbientKnown = false
        }
    }

    LaunchedEffect(item.id) {
        if (imdbId.isEmpty() && env.data.meta.tmdb.hasKey) {
            val tmdbNum = item.id.removePrefix("tmdb:").toIntOrNull()
            if (tmdbNum != null) {
                imdbId = env.data.meta.tmdb.imdbId(tmdbNum, item.type) ?: ""
            }
        }

        if (env.data.meta.tmdb.hasKey) {
            details = attempt { env.data.meta.tmdb.details(item.id, item.type) }
            val recs = attempt { env.data.meta.tmdb.recommendations(item.id, item.type) } ?: emptyList()
            similar = env.data.classifier.filter(recs, env.data.content.rules)
            trailers = attempt { env.data.meta.tmdb.videos(item.id, item.type) } ?: emptyList()
            logoUrl = attempt { env.data.meta.tmdb.logo(item.id, item.type) }
        }

        if (env.data.meta.mdblist.hasKey && imdbId.isNotEmpty()) {
            ratings = env.data.meta.mdblist.ratings(imdbId, item.type)
        }
    }

    fun openSourcePicker() {
        if (imdbId.isEmpty()) return
        env.shell.openSourcePicker(
            SourcePickerRequest(
                item = item,
                imdbId = imdbId,
                season = if (isSeries) selectedSeason else null,
                episode = if (isSeries) selectedEpisode else null,
                episodeTitle = episodes.firstOrNull { it.id == selectedEpisode }?.name,
                thumb = (if (isSeries) episodes.firstOrNull { it.id == selectedEpisode }?.image else null) ?: item.backdropUrl,
                logo = logoUrl,
                episodeInfo = episodes.firstOrNull { it.id == selectedEpisode },
                onSelect = { request -> onPlay(request) },
            ),
        )
    }

    LaunchedEffect(item.id, selectedSeason) {
        if (isSeries) {
            episodes = env.data.episodes.load(item.id, "series", selectedSeason) { imdbId.ifEmpty { null } }
        }
    }

    // Opened without a target (a poster, search, the Details menu): land on the episode you are in the middle of, or else
    // the one after the last you finished, rather than S1 E1. When the show has nothing further aired, the last one you
    // watched. Once the person picks an episode, this does not move the page.
    LaunchedEffect(item.id) {
        if (!isSeries || startSeason != null || startEpisode != null) return@LaunchedEffect
        val entry = env.data.history.entry(item.id) ?: return@LaunchedEffect
        val load: suspend (Int) -> List<EpisodeItem> = { s ->
            env.data.episodes.load(item.id, "series", s) { imdbId.ifEmpty { null } }
        }
        val target: Pair<Int, Int>? = if (entry.isInProgress) {
            entry.seasonEpisode?.let { it.season to it.episode }
        } else {
            UpNext.resolve(entry, load)?.let { it.season to it.episode }
                ?: (entry.watchedThrough ?: entry.seasonEpisode)?.let { it.season to it.episode }
        }
        if (target != null && !userChose) {
            selectedSeason = target.first
            selectedEpisode = target.second
        }
    }

    // Arrival only. When a season's episodes come in, the one-row list brings the selected episode into view, once and without
    // animation. Keyed on the list itself: keying on the season would scroll the previous season's list before the new one is
    // in, and an emptiness check would miss a season change made while a list is already showing. The guard keeps a season the
    // person picks from moving the row.
    LaunchedEffect(item.id, episodes) {
        if (userChose || episodes.isEmpty()) return@LaunchedEffect
        val index = episodes.indexOfFirst { it.id == selectedEpisode }
        if (index >= 0) episodeListState.scrollToItem(index)
    }

    val wideScroll = rememberScrollState()
    val feed = remember { FeedCollapse() }
    LaunchedEffect(wideScroll) {
        snapshotFlow { wideScroll.value }.collect { feed.vertical = (it / 300f).coerceIn(0f, 1f) }
    }
    val fold by animateFloatAsState(feed.progress, tween(140))
    val posterWidth = 440.dp * (1f - fold)

    Box(modifier.fillMaxSize()) {
        if (isWide) {
            CompositionLocalProvider(LocalFeedCollapse provides feed) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(posterWidth).fillMaxHeight().clipToBounds()) {
                        Box(
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(440.dp).fillMaxHeight()
                                .graphicsLayer { alpha = (1f - fold * 1.3f).coerceIn(0f, 1f) },
                        ) {
                            FadedPoster(item.largePosterUrl() ?: item.backdropUrl, Modifier.fillMaxSize())

                            Column(
                                Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 60.dp, bottom = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DetailHeaderInfo(item, details, ratings, logoUrl)
                                synopsis?.takeIf { it.isNotEmpty() }?.let { text ->
                                    ExpandableText(text, color = Color.White.copy(alpha = 0.85f))
                                }
                                DetailActionBar(
                                    item = item,
                                    isSeries = isSeries,
                                    season = selectedSeason,
                                    episode = selectedEpisode,
                                    isWatched = isWatched,
                                    isInLibrary = isInLibrary,
                                    onPlay = { openSourcePicker() },
                                    onToggleLibrary = { toggleLibrary() },
                                    onToggleWatched = {
                                        if (isWatched) env.data.history.unmarkWatched(item.id)
                                        else env.data.history.markWatched(item)
                                    },
                                )
                            }

                            GlassButton(
                                onClick = onBack,
                                symbol = "chevron.left",
                                size = 38.dp,
                                modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                            )
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(wideScroll)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            if (isSeries) {
                                SeasonsAndEpisodesSection(
                                    item = item,
                                    imdbId = imdbId,
                                    details = details,
                                    selectedSeason = selectedSeason,
                                    selectedEpisode = selectedEpisode,
                                    episodes = episodes,
                                    episodeListState = episodeListState,
                                    onSelectSeason = { userChose = true; selectedSeason = it; selectedEpisode = 1 },
                                    onSelectEpisode = { ep ->
                                        userChose = true
                                        selectedEpisode = ep.id
                                        openSourcePicker()
                                    },
                                    isEpisodeWatched = { epId ->
                                        env.data.history.isWatched(selectedSeason, epId, item.id)
                                    },
                                )
                            }

                            if (trailers.isNotEmpty()) {
                                TrailersSection(trailers)
                            }

                            if (similar.isNotEmpty()) {
                                CatalogRowView(
                                    row = CatalogRow("similar-${item.id}", "More like this", similar),
                                    onSelectTitle = onSelectTitle,
                                    onSeeAll = {},
                                )
                            }

                            AboutDetailsSection(details, isSeries)
                        }
                    }
                }
            }
            // The chevron that opens a folded hero. It sits outside the hero box, which is clipped to 0dp when fully folded.
            if (fold > 0.5f) {
                GlassButton(
                    onClick = {
                        feed.expand()
                        scope.launch { wideScroll.animateScrollTo(0) }
                    },
                    symbol = "chevron.right",
                    size = 38.dp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
        } else {
            val bottomInset = LocalBottomInset.current
            val scroll = rememberScrollState()
            val pageColour = PearTheme.colors.background
            val fade = remember(pageColour) { phoneDetailFade(pageColour) }
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(bottom = bottomInset + 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // The hero, as iOS's ParallaxHero: the art moves at half the scroll speed and dims as it scrolls away,
                    // the title block scrolls over it and fades before the top, and the bottom dissolves into the page.
                    Box(Modifier.fillMaxWidth().height(PhoneDetailHeroHeight).clipToBounds()) {
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                val s = scroll.value.toFloat()
                                val h = PhoneDetailHeroHeight.toPx()
                                translationY = s * 0.5f
                                alpha = 1f - 0.6f * (s / (h * 0.8f)).coerceIn(0f, 1f)
                            },
                        ) {
                            RemoteImage(item.backdropUrl ?: item.posterUrl, Modifier.fillMaxSize())
                        }
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .height(minOf(360.dp, PhoneDetailHeroHeight))
                                .background(fade),
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart)
                                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                                .graphicsLayer {
                                    val s = scroll.value.toFloat()
                                    val h = PhoneDetailHeroHeight.toPx()
                                    alpha = 1f - ((s - h * 0.3f) / (h * 0.25f)).coerceIn(0f, 1f)
                                },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DetailHeaderInfo(item, details, ratings, logoUrl)
                        }
                    }

                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DetailActionBar(
                            item = item,
                            isSeries = isSeries,
                            season = selectedSeason,
                            episode = selectedEpisode,
                            isWatched = isWatched,
                            isInLibrary = isInLibrary,
                            onPlay = { openSourcePicker() },
                            onToggleLibrary = { toggleLibrary() },
                            onToggleWatched = {
                                if (isWatched) env.data.history.unmarkWatched(item.id)
                                else env.data.history.markWatched(item)
                            },
                        )

                        details?.tagline?.takeIf { it.isNotEmpty() }?.let { tagline ->
                            PearText(tagline, style = PearTheme.type.footnote, color = PearTheme.colors.textTertiary)
                        }

                        synopsis?.takeIf { it.isNotEmpty() }?.let { text ->
                            ExpandableText(text)
                        }
                    }

                    if (isSeries) {
                        SeasonsAndEpisodesSection(
                            item = item,
                            imdbId = imdbId,
                            details = details,
                            selectedSeason = selectedSeason,
                            selectedEpisode = selectedEpisode,
                            episodes = episodes,
                            episodeListState = episodeListState,
                            onSelectSeason = { userChose = true; selectedSeason = it; selectedEpisode = 1 },
                            onSelectEpisode = { ep ->
                                userChose = true
                                selectedEpisode = ep.id
                                openSourcePicker()
                            },
                            isEpisodeWatched = { epId ->
                                env.data.history.isWatched(selectedSeason, epId, item.id)
                            },
                        )
                    }

                    if (trailers.isNotEmpty()) {
                        TrailersSection(trailers)
                    }

                    if (similar.isNotEmpty()) {
                        CatalogRowView(
                            row = CatalogRow("similar-${item.id}", "More like this", similar),
                            onSelectTitle = onSelectTitle,
                            onSeeAll = {},
                        )
                    }

                    Box(Modifier.padding(horizontal = 20.dp)) {
                        AboutDetailsSection(details, isSeries)
                    }
                }

                // The back button stays put while the page scrolls, as iOS's navigation bar does.
                GlassButton(
                    onClick = onBack,
                    symbol = "chevron.left",
                    size = 38.dp,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailHeaderInfo(
    item: MetaPreview,
    details: TmdbDetails?,
    ratings: List<MdbRating>,
    logoUrl: String?,
) {
    if (logoUrl != null) {
        Box(Modifier.height(56.dp).width(240.dp)) {
            RemoteImage(logoUrl, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
    } else {
        PearText(item.name, style = PearTheme.type.largeTitle.weight(FontWeight.Bold), maxLines = 2)
    }

    val metaParts = mutableListOf<String>()
    item.year?.let { metaParts.add(it.toString()) }
    details?.minutes?.let { metaParts.add("$it min") }
    details?.genres?.take(2)?.map { it.name }?.let { if (it.isNotEmpty()) metaParts.add(it.joinToString(", ")) }
    if (metaParts.isNotEmpty()) {
        PearText(metaParts.joinToString("  ·  "), style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
    }

    if (ratings.isNotEmpty()) {
        DragLazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ratings) { RatingBadge(it) }
        }
    }
}

@Composable
private fun DetailActionBar(
    item: MetaPreview,
    isSeries: Boolean,
    season: Int,
    episode: Int,
    isWatched: Boolean,
    isInLibrary: Boolean,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(
            label = if (isSeries) "Play S$season:E$episode" else "Play",
            onClick = onPlay,
            modifier = Modifier.weight(1f),
        )

        GlassButton(
            onClick = onToggleLibrary,
            symbol = if (isInLibrary) "bookmark.fill" else "bookmark",
            size = 44.dp,
        )

        GlassButton(
            onClick = onToggleWatched,
            symbol = if (isWatched) "checkmark.circle.fill" else "checkmark.circle",
            iconTint = if (isWatched) PearTheme.colors.accent else null,
            size = 44.dp,
        )
    }
}

@Composable
private fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, PearMotion.press<Float>())
    val sheen by animateFloatAsState(if (hovered) 0.12f else 0f, PearMotion.snappy<Float>(PearMotion.FAST))
    Box(
        modifier
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50))
            .background(c.accent)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().background(Color.White.copy(alpha = sheen)))
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PearIcon("play.fill", size = 20.dp, tint = c.onAccent)
            PearText(label, style = PearTheme.type.headline.weight(FontWeight.SemiBold), color = c.onAccent, maxLines = 1)
        }
    }
}

@Composable
private fun SeasonsAndEpisodesSection(
    item: MetaPreview,
    imdbId: String,
    details: TmdbDetails?,
    selectedSeason: Int,
    selectedEpisode: Int,
    episodes: List<EpisodeItem>,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (EpisodeItem) -> Unit,
    isEpisodeWatched: (Int) -> Boolean,
    episodeListState: LazyListState,
) {
    val env = LocalAppEnv.current
    val seasons = details?.seasons?.filter { (it.episodeCount ?: 1) > 0 } ?: (1..1).map { SeasonInfo(it, "Season 1", it) }

    val isWide = LocalWideLayout.current
    val scale = if (isWide) WideScale.CARD else 1f
    val twoRows = rememberPref(env.data.prefs, PrefKeys.DESKTOP_EPISODES_TWO_ROWS) {
        it.bool(PrefKeys.DESKTOP_EPISODES_TWO_ROWS, false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PearText("Seasons", style = PearTheme.type.title3.weight(FontWeight.Bold), modifier = Modifier.weight(1f))
            if (isWide && episodes.isNotEmpty()) {
                DevChip("One row", !twoRows) { env.data.prefs.setBool(PrefKeys.DESKTOP_EPISODES_TWO_ROWS, false) }
                DevChip("Two rows", twoRows) { env.data.prefs.setBool(PrefKeys.DESKTOP_EPISODES_TWO_ROWS, true) }
            }
        }

        DragLazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons) { s ->
                val num = s.seasonNumber
                val anchor = remember { MenuAnchor() }
                Box(Modifier.menuAnchor(anchor)) {
                    DevChip(
                        s.title,
                        selectedSeason == num,
                        onClick = { onSelectSeason(num) },
                        onSecondary = {
                            env.shell.openContextMenu(
                                ContextMenuRequest(
                                    anchor.rect(),
                                    seasonMenuItems(env, item, num, details, selectedSeason, episodes) { onSelectSeason(num) },
                                ),
                            )
                        },
                    )
                }
            }
        }

        if (episodes.isNotEmpty()) {
            if (isWide && twoRows) {
                DragLazyGrid(
                    rows = 2,
                    modifier = Modifier.fillMaxWidth().height((292f * scale).dp * 2f + 16.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    gridItems(episodes) { ep ->
                        EpisodeCard(
                            ep = ep,
                            isWatched = isEpisodeWatched(ep.id),
                            isSelected = ep.id == selectedEpisode,
                            onClick = { onSelectEpisode(ep) },
                            onMenu = { rect ->
                                env.shell.openContextMenu(
                                    ContextMenuRequest(rect, episodeMenuItems(env, item, imdbId, selectedSeason, ep) { onSelectEpisode(ep) }),
                                )
                            },
                            scale = scale,
                        )
                    }
                }
            } else {
                DragLazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    state = episodeListState,
                ) {
                    items(episodes) { ep ->
                        EpisodeCard(
                            ep = ep,
                            isWatched = isEpisodeWatched(ep.id),
                            isSelected = ep.id == selectedEpisode,
                            onClick = { onSelectEpisode(ep) },
                            onMenu = { rect ->
                                env.shell.openContextMenu(
                                    ContextMenuRequest(rect, episodeMenuItems(env, item, imdbId, selectedSeason, ep) { onSelectEpisode(ep) }),
                                )
                            },
                            scale = scale,
                        )
                    }
                }
            }
        }
    }
}

private fun seasonMenuItems(
    env: AppEnv,
    item: MetaPreview,
    season: Int,
    details: TmdbDetails?,
    selectedSeason: Int,
    episodes: List<EpisodeItem>,
    onShow: () -> Unit,
): List<ContextMenuItem> {
    val history = env.data.history
    val count = details?.seasons?.firstOrNull { it.seasonNumber == season }?.episodeCount
        ?: episodes.takeIf { season == selectedSeason && it.isNotEmpty() }?.size
        ?: 10
    val minutes = (details?.minutes ?: 45).toDouble()
    val watched = history.isWatched(season, count, item.id)
    return listOf(
        ContextMenuItem("Show Season $season", "rectangle.stack") { onShow() },
        ContextMenuItem(
            label = if (watched) "Unmark Season as Watched" else "Mark Season as Watched",
            symbol = if (watched) "circle" else "checkmark.circle",
        ) {
            if (watched) {
                history.unmarkThrough(episode = 1, season = season, item = item)
            } else {
                history.markWatched(item, season = season, episode = count, duration = max(minutes, 20.0) * count * 60.0)
            }
        },
    )
}

@Composable
private fun TrailersSection(trailers: List<Video>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PearText("Trailers & Extras", style = PearTheme.type.title3.weight(FontWeight.Bold), modifier = Modifier.padding(horizontal = 20.dp))
        DragLazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(trailers) { trailer ->
                TrailerCard(trailer)
            }
        }
    }
}

@Composable
private fun AboutDetailsSection(details: TmdbDetails?, isSeries: Boolean) {
    if (details == null) return

    val rows = mutableListOf<Pair<String, String>>()
    if (isSeries) {
        details.networks?.firstOrNull()?.name?.let { rows.add("Network" to it) }
        details.status?.let { rows.add("Status" to it) }
        details.firstAirDate?.let { rows.add("First Aired" to it) }
        details.numberOfSeasons?.let { rows.add("Seasons" to it.toString()) }
        details.numberOfEpisodes?.let { rows.add("Episodes" to it.toString()) }
    } else {
        details.releaseDate?.let { rows.add("Released" to it) }
        details.credits?.crew?.firstOrNull { it.job == "Director" }?.name?.let { rows.add("Director" to it) }
    }
    details.genres?.map { it.name }?.let { if (it.isNotEmpty()) rows.add("Genres" to it.joinToString(", ")) }
    details.credits?.cast?.take(5)?.map { it.name }?.let { if (it.isNotEmpty()) rows.add("Cast" to it.joinToString(", ")) }

    if (rows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText(if (isSeries) "About the series" else "About the movie", style = PearTheme.type.title3.weight(FontWeight.Bold))
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PearText(label, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                PearText(value, style = PearTheme.type.footnote.weight(FontWeight.Medium), color = PearTheme.colors.textPrimary)
            }
        }
    }
}
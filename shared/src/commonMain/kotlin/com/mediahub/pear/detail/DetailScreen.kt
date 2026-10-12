package com.mediahub.pear.detail

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.mediahub.pear.shell.DragLazyGrid
import com.mediahub.pear.shell.DragLazyRow
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.data.PrefKeys
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.history.LibraryStatus
import com.mediahub.pear.home.CatalogRowView
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
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.SourcePickerRequest
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import com.mediahub.pear.home.fallbackHeroAmbientColor
import com.mediahub.pear.net.rememberDominantColor
import com.mediahub.pear.shell.FadedPoster
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.FeedCollapse
import kotlin.math.max
import com.mediahub.pear.shell.LocalFeedCollapse

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


    // Library and watched states
    val isWatched = env.data.history.entry(item.id)?.isTitleWatched == true
    val isInLibrary = env.data.library.entry(item.id) != null

    LaunchedEffect(item.id) {
        // Resolve IMDb ID if needed
        if (imdbId.isEmpty() && env.data.meta.tmdb.hasKey) {
            val tmdbNum = item.id.removePrefix("tmdb:").toIntOrNull()
            if (tmdbNum != null) {
                imdbId = env.data.meta.tmdb.imdbId(tmdbNum, item.type) ?: ""
            }
        }

        // Details & Recommendations
        if (env.data.meta.tmdb.hasKey) {
            details = attempt { env.data.meta.tmdb.details(item.id, item.type) }
            val recs = attempt { env.data.meta.tmdb.recommendations(item.id, item.type) } ?: emptyList()
            similar = env.data.classifier.filter(recs, env.data.content.rules)
            trailers = attempt { env.data.meta.tmdb.videos(item.id, item.type) } ?: emptyList()
            logoUrl = attempt { env.data.meta.tmdb.logo(item.id, item.type) }
        }

        // Ratings from MDBList
        if (env.data.meta.mdblist.hasKey && imdbId.isNotEmpty()) {
            ratings = env.data.meta.mdblist.ratings(imdbId, item.type)
        }
    }

    // The picker is drawn over the whole app by the shell (see ShellOverlays), not here: a sheet inside the page would
    // sit in the blur source and come out transparent.
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

    // Load episodes when season changes
    LaunchedEffect(item.id, selectedSeason) {
        if (isSeries) {
            episodes = env.data.episodes.load(item.id, "series", selectedSeason) { imdbId.ifEmpty { null } }
        }
    }

    // Wide layout: the poster column folds away as the page scrolls, and as any row on the page is dragged (the episode
    // cards, More like this), the way Home's hero does. Rows report to LocalFeedCollapse; the page's scroll sets the
    // vertical part here.
    val wideScroll = rememberScrollState()
    val feed = remember { FeedCollapse() }
    LaunchedEffect(wideScroll) {
        snapshotFlow { wideScroll.value }.collect { feed.vertical = (it / 300f).coerceIn(0f, 1f) }
    }
    val fold by animateFloatAsState(feed.progress, tween(140))
    val posterWidth = 440.dp * (1f - fold)

    Box(modifier.fillMaxSize()) {
        if (isWide) {
            // Split layout for wide screens
            CompositionLocalProvider(LocalFeedCollapse provides feed) {
            Row(Modifier.fillMaxSize()) {
                // Left column: the poster, frameless, dissolving into the page's colour wash.
                val dominant = rememberDominantColor(item.posterUrl ?: item.backdropUrl)
                LaunchedEffect(item.id, dominant) {
                    env.shell.heroAmbientColor = dominant ?: fallbackHeroAmbientColor(item.name)
                }
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
                        DetailActionBar(
                            item = item,
                            isSeries = isSeries,
                            season = selectedSeason,
                            episode = selectedEpisode,
                            isWatched = isWatched,
                            isInLibrary = isInLibrary,
                            onPlay = { openSourcePicker() },
                            onToggleLibrary = {
                                if (isInLibrary) env.data.library.remove(item.id)
                                else env.data.library.set(item, LibraryStatus.PlanToWatch)
                            },
                            onToggleWatched = {
                                if (isWatched) env.data.history.unmarkWatched(item.id)
                                else env.data.history.markWatched(item)
                            },
                        )
                    }

                    // Back button top leading
                    GlassButton(
                        onClick = onBack,
                        symbol = "chevron.left",
                        size = 38.dp,
                        modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                    )
                }
                }

                // Right column: Seasons, Episodes, Trailers, Recommendations, About Details
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
                                details = details,
                                selectedSeason = selectedSeason,
                                selectedEpisode = selectedEpisode,
                                episodes = episodes,
                                onSelectSeason = { selectedSeason = it; selectedEpisode = 1 },
                                onSelectEpisode = { ep ->
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
        } else {
            // Stacked layout for compact / phone screens
            val bottomInset = LocalBottomInset.current
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomInset + 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Top Hero Backdrop
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RemoteImage(item.backdropUrl ?: item.posterUrl, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.3f to Color.Transparent,
                                1f to PearTheme.colors.background,
                            ),
                        ),
                    )

                    GlassButton(
                        onClick = onBack,
                        symbol = "chevron.left",
                        size = 38.dp,
                        modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                    )

                    Column(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp),
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
                        onToggleLibrary = {
                            if (isInLibrary) env.data.library.remove(item.id)
                            else env.data.library.set(item, LibraryStatus.PlanToWatch)
                        },
                        onToggleWatched = {
                            if (isWatched) env.data.history.unmarkWatched(item.id)
                            else env.data.history.markWatched(item)
                        },
                    )

                    details?.tagline?.takeIf { it.isNotEmpty() }?.let { tagline ->
                        PearText(tagline, style = PearTheme.type.footnote, color = PearTheme.colors.textTertiary)
                    }

                    val synopsis = details?.overview ?: item.description
                    synopsis?.takeIf { it.isNotEmpty() }?.let { text ->
                        ExpandableText(text)
                    }
                }

                if (isSeries) {
                    SeasonsAndEpisodesSection(
                        item = item,
                        details = details,
                        selectedSeason = selectedSeason,
                        selectedEpisode = selectedEpisode,
                        episodes = episodes,
                        onSelectSeason = { selectedSeason = it; selectedEpisode = 1 },
                        onSelectEpisode = { ep ->
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
        }
    }
}

// MARK: Header Info & Action Bar

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
        GlassButton(
            onClick = onPlay,
            label = if (isSeries) "Play S$season:E$episode" else "Play",
            symbol = "play.fill",
            tint = PearTheme.colors.accent,
            modifier = Modifier.weight(1f),
        )

        // Bookmark / Library toggle
        GlassButton(
            onClick = onToggleLibrary,
            symbol = if (isInLibrary) "bookmark.fill" else "bookmark",
            size = 44.dp,
        )

        // Watched toggle
        GlassButton(
            onClick = onToggleWatched,
            symbol = if (isWatched) "checkmark.circle.fill" else "checkmark.circle",
            tint = if (isWatched) PearTheme.colors.accent else null,
            size = 44.dp,
        )
    }
}

// MARK: Seasons & Episodes

@Composable
private fun SeasonsAndEpisodesSection(
    item: MetaPreview,
    details: TmdbDetails?,
    selectedSeason: Int,
    selectedEpisode: Int,
    episodes: List<EpisodeItem>,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (EpisodeItem) -> Unit,
    isEpisodeWatched: (Int) -> Boolean,
) {
    val env = LocalAppEnv.current
    val seasons = details?.seasons?.filter { (it.episodeCount ?: 1) > 0 } ?: (1..1).map { SeasonInfo(it, "Season 1", it) }

    // Wide layout only: episode cards are a third bigger and can sit on two rows. The phone layout is always one row
    // at the normal size.
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

        // Season pills
        DragLazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons) { s ->
                val num = s.seasonNumber
                val anchor = remember { MenuAnchor() }
                Box(Modifier.menuAnchor(anchor)) {
                    DevChip(s.title, selectedSeason == num) {
                        // Wide layout: a click opens the season's menu (its first item shows the season). Phone: a tap selects.
                        if (isWide) {
                            env.shell.openContextMenu(
                                ContextMenuRequest(
                                    anchor.rect(),
                                    seasonMenuItems(env, item, num, details, selectedSeason, episodes) { onSelectSeason(num) },
                                ),
                            )
                        } else {
                            onSelectSeason(num)
                        }
                    }
                }
            }
        }

        // Episode carousel
        if (episodes.isNotEmpty()) {
            if (isWide && twoRows) {
                // Two rows: the grid is given the height of two cards and the gap between them.
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
                            scale = scale,
                        )
                    }
                }
            } else {
                DragLazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(episodes) { ep ->
                        EpisodeCard(
                            ep = ep,
                            isWatched = isEpisodeWatched(ep.id),
                            isSelected = ep.id == selectedEpisode,
                            onClick = { onSelectEpisode(ep) },
                            scale = scale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A season's menu (`seasonMenu` in DetailView.swift): show it, and mark it watched or unmark it. A season is watched
 * when everything up to its last episode is.
 */
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

// MARK: Trailers

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

// MARK: About Metadata Rows

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
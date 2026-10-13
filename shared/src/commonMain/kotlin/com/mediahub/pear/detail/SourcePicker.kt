package com.mediahub.pear.detail

import com.mediahub.pear.model.StreamSize
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.ui.graphics.Brush
import com.mediahub.pear.p2p.p2pSource
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.Pin
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.attempt
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.Route
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.SourcePickerRequest
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.home.scaledBy
import com.mediahub.pear.theme.weight

/**
 * The source picker, drawn over the whole app by the shell. Phone: a bottom sheet ([SourceSheet]). Wide: a panel in the
 * middle of the window, with the sources as a grid of cards, so a widescreen has room for them.
 */
@Composable
fun SourcePickerOverlay(request: SourcePickerRequest, onClose: () -> Unit) {
    val wide = LocalWideLayout.current
    val swallow = remember { MutableInteractionSource() }
    val select: (PlayRequest) -> Unit = { picked ->
        onClose()
        request.onSelect(picked)
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
        contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
    ) {
        // Taps on the panel itself don't reach the scrim, so they don't close the picker.
        val panel = Modifier.clickable(interactionSource = swallow, indication = null) {}
        if (wide) {
            WideSourcePicker(request, onSelect = select, onClose = onClose, modifier = panel)
        } else {
            SourceSheet(
                item = request.item,
                imdbId = request.imdbId,
                season = request.season,
                episode = request.episode,
                episodeTitle = request.episodeTitle,
                onSelectSource = select,
                onClose = onClose,
                modifier = panel,
                thumb = request.thumb,
                logo = request.logo,
            )
        }
    }
}

/**
 * Opens the source picker for a title or episode from anywhere (Continue Watching uses it). Resolves the IMDb id, the
 * episode and the logo the way the details page does. Returns false when there is no IMDb id to search with, so the
 * caller can fall back to the details page.
 */
suspend fun openSourceChooser(
    env: AppEnv,
    item: MetaPreview,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    thumb: String?,
): Boolean {
    val tmdb = env.data.meta.tmdb
    val tmdbNum = item.id.removePrefix("tmdb:").toIntOrNull()
    val imdb = if (item.id.startsWith("tt")) item.id else tmdbNum?.let { attempt { tmdb.imdbId(it, item.type) } }
    if (imdb.isNullOrBlank()) return false
    val episodeInfo = if (season != null && episode != null) {
        attempt { env.data.episodes.load(item.id, "series", season) { imdb } }?.firstOrNull { it.id == episode }
    } else {
        null
    }
    val logo = item.logo ?: attempt { tmdb.logo(item.id, item.type) }
    env.shell.openSourcePicker(
        SourcePickerRequest(
            item = item,
            imdbId = imdb,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle ?: episodeInfo?.name,
            thumb = thumb ?: episodeInfo?.image ?: item.backdropUrl,
            logo = logo,
            episodeInfo = episodeInfo,
            onSelect = { request ->
                env.shell.closeSourcePicker()
                env.shell.currentNav().push(Route.Player(request))
            },
        ),
    )
    return true
}

/** Episode stills are cut at w300 on the list; the picker asks for the original file. */
private fun largeStill(url: String?): String? = url?.replace(Regex("/t/p/w\\d+/"), "/t/p/original/")

/**
 * Wide layout: two thirds for the episode (image, logo, ratings, synopsis), one third for the sources, stacked down the
 * page. The panel is glass, and so is every card and chip in it.
 */
@Composable
private fun WideSourcePicker(
    request: SourcePickerRequest,
    onSelect: (PlayRequest) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val groups = remember { mutableStateListOf<Pair<Addon, List<StreamItem>>>() }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf<String?>(null) }
    var ratings by remember { mutableStateOf<List<MdbRating>>(emptyList()) }
    val pinned = env.data.pinned.pin(request.imdbId)

    // The same query the bottom sheet runs: every add-on that serves this title or episode.
    LaunchedEffect(request.imdbId, request.season, request.episode) {
        groups.clear()
        loading = true
        val sid = if (request.season != null && request.episode != null) "${request.imdbId}:${request.season}:${request.episode}" else request.imdbId
        env.data.addonClient.streamUpdates(sid, request.item.type, env.data.addons.enabledAddons).collect { group ->
            groups.add(group)
        }
        loading = false
    }
    LaunchedEffect(request.imdbId) {
        ratings = if (env.data.meta.mdblist.hasKey) {
            attempt { env.data.meta.mdblist.ratings(request.imdbId, request.item.type) } ?: emptyList()
        } else {
            emptyList()
        }
    }

    val ordered = AddonClient.ordered(groups.toList(), env.data.addons.enabledAddons)
    val shown = ordered.filter { filter == null || it.first.id == filter }

    GlassSurface(
        modifier = modifier.fillMaxWidth(0.86f).widthIn(max = 1280.dp).fillMaxHeight(0.86f).heightIn(max = 800.dp),
        shape = RoundedCornerShape(PearRadii.sheet),
    ) {
        Row(Modifier.fillMaxSize()) {
            EpisodePane(request, ratings, Modifier.weight(2f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(PearTheme.colors.hairline))
            SourcesPane(
                ordered = ordered,
                owner = SourceOwner(
                    imdb = request.imdbId,
                    item = request.item,
                    season = request.season,
                    episode = request.episode,
                    episodeTitle = request.episodeTitle,
                    thumb = request.thumb,
                    logo = request.logo,
                    runtime = request.episodeInfo?.runtime,
                ),
                shown = shown,
                filter = filter,
                onFilter = { filter = it },
                loading = loading,
                pinned = pinned,
                onSelect = { addon, stream -> onSelect(sourcePlayRequest(request, addon, stream)) },
                onClose = onClose,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * The left two thirds: the episode's still fills the top two thirds of the pane, edge to edge. Its bottom fades into the dark,
 * and on that fade sit the show's logo, the episode's name, the MDBList ratings, the synopsis and the details (runtime, air date).
 */
@Composable
private fun EpisodePane(request: SourcePickerRequest, ratings: List<MdbRating>, modifier: Modifier) {
    val info = request.episodeInfo
    val image = largeStill(info?.image) ?: request.thumb ?: request.item.backdropUrl ?: request.item.posterUrl
    val kicker = if (request.season != null && request.episode != null) {
        "${request.item.typeLabel.uppercase()} · S${request.season} · E${request.episode}"
    } else {
        request.item.typeLabel.uppercase()
    }
    val episodeName = info?.name?.takeIf { it.isNotBlank() } ?: request.episodeTitle
    val synopsis = info?.overview?.takeIf { it.isNotBlank() } ?: request.item.description
    val details = listOfNotNull(formatEpisodeRuntime(info?.runtime), episodeAirsOn(info?.airDate)).joinToString(" · ")
    val dark = PearTheme.colors.background
    BoxWithConstraints(modifier) {
        val paneHeight = maxHeight
        Box(Modifier.fillMaxWidth().height(paneHeight * 0.67f)) {
            RemoteImage(image, Modifier.fillMaxSize())
        }
        // Clear over the still, then dark over the lower part of the pane, where the text goes.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.2f to Color.Transparent,
                    0.67f to dark.copy(alpha = 0.92f),
                    1f to dark,
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .heightIn(max = paneHeight * 0.62f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val logo = request.logo
            if (logo != null) {
                Box(Modifier.widthIn(max = 420.dp).height(64.dp)) {
                    RemoteImage(logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            } else {
                PearText(request.item.name, style = PearTheme.type.largeTitle.weight(FontWeight.Bold), maxLines = 2)
            }
            PearText(kicker, style = PearTheme.type.caption2.weight(FontWeight.Bold), color = PearTheme.colors.textTertiary, maxLines = 1)
            episodeName?.let {
                PearText(it, style = PearTheme.type.title3.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary, maxLines = 1)
            }
            if (ratings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ratings.forEach { RatingBadge(it) }
                }
            }
            synopsis?.let {
                PearText(it, style = PearTheme.type.body, color = PearTheme.colors.textPrimary)
            }
            if (details.isNotEmpty()) {
                PearText(details, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** The right third: the add-on filter, then every source stacked down the page, grouped by add-on. */
@Composable
private fun SourcesPane(
    ordered: List<Pair<Addon, List<StreamItem>>>,
    owner: SourceOwner,
    shown: List<Pair<Addon, List<StreamItem>>>,
    filter: String?,
    onFilter: (String?) -> Unit,
    loading: Boolean,
    pinned: Pin?,
    onSelect: (Addon, StreamItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val total = ordered.sumOf { it.second.size }
    // Live: the switch in Settings changes these cards at once.
    val env = LocalAppEnv.current
    val p2pReady by env.data.p2p.canStream.collectAsState()
    val p2pOn by env.data.p2p.enabled.collectAsState()
    Column(modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PearText("Sources", style = PearTheme.type.title3.weight(FontWeight.Bold))
                PearText("$total found", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
            }
            GlassButton(onClick = onClose, symbol = "xmark", size = 36.dp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevChip("All ($total)", filter == null) { onFilter(null) }
            ordered.forEach { (addon, items) ->
                DevChip("${addon.manifest.name} (${items.size})", filter == addon.id) { onFilter(addon.id) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                shown.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PearText("Finding sources…", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PearText("No playable sources found for this title.", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    shown.forEach { (addon, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PearText(
                                addon.manifest.name.uppercase(),
                                style = PearTheme.type.caption2.weight(FontWeight.Bold),
                                color = PearTheme.colors.textTertiary,
                                maxLines = 1,
                            )
                            items.forEach { stream ->
                                SourceCard(
                                    stream = stream,
                                    addon = addon,
                                    owner = owner,
                                    isPinned = pinned?.addonID == addon.id && pinned.signature == stream.signature,
                                    playable = stream.isPlayable(p2pReady),
                                    onClick = { onSelect(addon, stream) },
                                )
                            }
                            val unavailable = items.count { it.isTorrent && !it.isPlayable(p2pReady) }
                            if (unavailable > 0) {
                                PearText(
                                    if (!p2pOn) {
                                        "$unavailable torrent source${if (unavailable == 1) "" else "s"} unavailable: P2P is off. " +
                                            "Turn it on in Settings → Peer-to-peer."
                                    } else {
                                        "$unavailable torrent source${if (unavailable == 1) "" else "s"} unavailable on this network."
                                    },
                                    style = PearTheme.type.caption,
                                    color = PearTheme.colors.textTertiary,
                                )
                            }
                        }
                    }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PearIcon("waveform", size = 14.dp, tint = PearTheme.colors.textSecondary)
                            PearText("Checking more add-ons…", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One source, as a glass card: its name and size, a small download button for a direct link, the download's status, and the
 * description. A left click picks it; a right click (or a long press) opens its menu.
 */
@Composable
private fun SourceCard(
    stream: StreamItem,
    addon: Addon,
    owner: SourceOwner,
    isPinned: Boolean,
    playable: Boolean,
    onClick: () -> Unit,
) {
    val c = PearTheme.colors
    val env = LocalAppEnv.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val anchor = remember { MenuAnchor() }
    val detail = stream.description ?: stream.title
    GlassSurface(
        Modifier.fillMaxWidth()
            .alpha(if (playable) 1f else 0.45f)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickOrMenu(
                source,
                onClick = { if (playable) onClick() },
                onMenu = {
                    env.shell.openContextMenu(ContextMenuRequest(anchor.rect(), sourceMenuItems(env, owner, addon, stream, isPinned)))
                },
            ),
        RoundedCornerShape(PearRadii.card),
        highlight = hovered,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PearText(
                    stream.name ?: stream.title ?: "Stream",
                    style = PearTheme.type.headline,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                stream.sizeBytes?.let {
                    PearText(StreamSize.format(it), style = PearTheme.type.caption, color = c.textSecondary, maxLines = 1)
                }
                if (stream.isTorrent) {
                    PearText("P2P", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = c.textSecondary)
                }
                if (isPinned) PearIcon("pin.fill", size = 12.dp, tint = c.accent)
                if (stream.url != null) SourceDownloadButton(env, owner, addon, stream)
            }
            if (detail != null && detail != stream.name) {
                PearText(detail, style = PearTheme.type.footnote, color = c.textSecondary, maxLines = 3)
            }
            if (stream.url != null) SourceDownloadStatus(env, owner)
        }
    }
}

/** The request for one stream, built the way the bottom sheet builds it. */
private fun sourcePlayRequest(r: SourcePickerRequest, addon: Addon, stream: StreamItem): PlayRequest = PlayRequest(
    url = stream.url ?: "p2p://${stream.infoHash}",
    headers = stream.requestHeaders,
    item = r.item,
    key = if (r.season != null) "${r.season}:${r.episode}" else "movie",
    imdb = r.imdbId,
    season = r.season,
    episode = r.episode,
    episodeTitle = r.episodeTitle,
    logo = r.logo,
    thumb = r.thumb,
    p2p = stream.p2pSource(),
    sourceAddonID = addon.id,
    sourceSignature = stream.signature,
)

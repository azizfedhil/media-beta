package com.mediahub.pear.detail

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.SourcePickerRequest
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
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

    val ordered = AddonClient.ordered(groups.toList(), env.data.addons.enabledAddons)
    val cards = ordered
        .filter { filter == null || it.first.id == filter }
        .flatMap { (addon, items) -> items.map { addon to it } }
    val target = if (request.season != null && request.episode != null) {
        "S${request.season} · E${request.episode}" + (request.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    } else {
        request.item.name
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth(0.84f).widthIn(max = 980.dp).fillMaxHeight(0.84f).heightIn(max = 720.dp),
        shape = RoundedCornerShape(PearRadii.sheet),
    ) {
        Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PearText("Choose a source", style = PearTheme.type.title2.weight(FontWeight.Bold))
                    PearText(target, style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary, maxLines = 1)
                }
                GlassButton(onClick = onClose, symbol = "xmark", size = 36.dp)
            }

            // Add-on filter: All, then one chip per add-on that answered.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DevChip("All (${ordered.sumOf { it.second.size }})", filter == null) { filter = null }
                ordered.forEach { (addon, items) ->
                    DevChip("${addon.manifest.name} (${items.size})", filter == addon.id) { filter = addon.id }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    cards.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PearText("Finding sources…", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                    }
                    cards.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PearText("No playable sources found for this title.", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 260.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(cards, key = { (addon, stream) -> "${addon.id}|${stream.id}" }) { (addon, stream) ->
                            SourceCard(
                                addon = addon,
                                stream = stream,
                                isPinned = pinned?.addonID == addon.id && pinned.signature == stream.signature,
                                onClick = { onSelect(sourcePlayRequest(request, addon, stream)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One source: its add-on, name, description and whether it is the pinned one. Torrents are marked P2P. */
@Composable
private fun SourceCard(addon: Addon, stream: StreamItem, isPinned: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PearRadii.card)
    val detail = stream.description ?: stream.title
    Column(
        Modifier.fillMaxWidth().height(150.dp)
            .clip(shape)
            .background(if (hovered) c.fill else c.fillSubtle)
            .border(if (isPinned) 1.5.dp else 1.dp, if (isPinned) c.accent else c.outline, shape)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PearText(
                addon.manifest.name.uppercase(),
                style = PearTheme.type.caption2.weight(FontWeight.Bold),
                color = c.textTertiary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (stream.isTorrent) {
                PearText("P2P", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = c.textSecondary)
            }
            if (isPinned) PearIcon("pin.fill", size = 12.dp, tint = c.accent)
        }
        PearText(stream.name ?: stream.title ?: "Stream", style = PearTheme.type.headline, maxLines = 2)
        if (detail != null && detail != stream.name) {
            PearText(detail, style = PearTheme.type.footnote, color = c.textSecondary, maxLines = 3)
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
    sourceAddonID = addon.id,
    sourceSignature = stream.signature,
)

package com.mediahub.pear.detail

import com.mediahub.pear.p2p.p2pSource
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import com.mediahub.pear.glass.GlassSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassSheet
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun SourceSheet(
    item: MetaPreview,
    imdbId: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    onSelectSource: (PlayRequest) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Episode still (or the movie backdrop): shown on the Continue Watching card. */
    thumb: String? = null,
    /** Title logo, for the player's paused state. */
    logo: String? = null,
) {
    val env = LocalAppEnv.current
    // Live: the switch in Settings changes these rows at once.
    val p2pReady by env.data.p2p.canStream.collectAsState()
    val p2pOn by env.data.p2p.enabled.collectAsState()
    val streams = remember { mutableStateListOf<Pair<Addon, List<StreamItem>>>() }
    var loading by remember { mutableStateOf(true) }
    var selectedAddonFilter by remember { mutableStateOf<String?>(null) }
    val pinned = env.data.pinned.pin(imdbId)

    LaunchedEffect(imdbId, season, episode) {
        streams.clear()
        loading = true
        val sid = if (season != null && episode != null) "$imdbId:$season:$episode" else imdbId
        val addons = env.data.addons.enabledAddons
        env.data.addonClient.streamUpdates(sid, item.type, addons).collect { group ->
            streams.add(group)
        }
        loading = false
    }

    GlassSheet(modifier.width(460.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                PearText("Sources", style = PearTheme.type.headline)
                val target = if (season != null && episode != null) "S$season · E$episode" else item.name
                PearText(target, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
            }
            GlassButton(onClick = onClose, symbol = "xmark", size = 32.dp)
        }

        // Add-on filter chips if multiple add-ons answered
        if (streams.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DevChip("All (${streams.sumOf { it.second.size }})", selectedAddonFilter == null) {
                    selectedAddonFilter = null
                }
                streams.forEach { (addon, list) ->
                    DevChip("${addon.manifest.name} (${list.size})", selectedAddonFilter == addon.id) {
                        selectedAddonFilter = addon.id
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loading && streams.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    PearText("Finding sources…", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
            } else if (!loading && streams.all { it.second.isEmpty() }) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    PearText("No playable sources found.", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                }
            }

            val filtered = streams.filter { selectedAddonFilter == null || it.first.id == selectedAddonFilter }
            filtered.forEach { (addon, items) ->
                if (items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PearText(
                            text = addon.manifest.name.uppercase(),
                            style = PearTheme.type.caption2.weight(FontWeight.Bold),
                            color = PearTheme.colors.textTertiary,
                        )
                        items.forEach { stream ->
                            val isPinned = pinned?.addonID == addon.id && pinned.signature == stream.signature
                            SourceItemRow(
                                stream = stream,
                                addon = addon,
                                isPinned = isPinned,
                                playable = stream.isPlayable(p2pReady),
                                onClick = {
                                    val streamUrl = stream.url ?: "p2p://${stream.infoHash}"
                                    onSelectSource(
                                        PlayRequest(
                                            url = streamUrl,
                                            headers = stream.requestHeaders,
                                            item = item,
                                            key = if (season != null) "$season:$episode" else "movie",
                                            imdb = imdbId,
                                            season = season,
                                            episode = episode,
                                            episodeTitle = episodeTitle,
                                            logo = logo,
                                            thumb = thumb,
                                            p2p = stream.p2pSource(),
                                            sourceAddonID = addon.id,
                                            sourceSignature = stream.signature,
                                        ),
                                    )
                                },
                            )
                        }
                        // Why a torrent can't be played, under its group.
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
            }
        }
    }
}

@Composable
private fun SourceItemRow(
    stream: StreamItem,
    addon: Addon,
    isPinned: Boolean,
    playable: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PearRadii.sm)
    GlassSurface(
        Modifier.fillMaxWidth().alpha(if (playable) 1f else 0.45f)
            .then(if (playable) Modifier.clickable(onClick = onClick) else Modifier),
        shape,
        wash = if (isPinned) PearTheme.colors.accent.copy(alpha = 0.3f) else null,
    ) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PearText(
                    text = stream.name ?: stream.title ?: "Stream",
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (isPinned) {
                    PearIcon("pin.fill", size = 12.dp, tint = PearTheme.colors.accent)
                }
            }
            stream.description?.let { desc ->
                PearText(desc, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 2)
            }
        }

        if (stream.isTorrent) {
            Box(
                Modifier.clip(RoundedCornerShape(PearRadii.xs))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                PearText("P2P", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = PearTheme.colors.textSecondary)
            }
        }
    }
    }
}

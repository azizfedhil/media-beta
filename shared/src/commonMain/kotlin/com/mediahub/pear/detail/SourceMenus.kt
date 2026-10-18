package com.mediahub.pear.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.Pin
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.DownloadRecord
import com.mediahub.pear.model.DownloadState
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.model.StreamSize
import com.mediahub.pear.model.downloadKey
import com.mediahub.pear.model.newDownload
import com.mediahub.pear.downloads.DownloadManager
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.model.downloadUrl
import com.mediahub.pear.model.etaText
import com.mediahub.pear.model.speedText
import com.mediahub.pear.model.forSource
import com.mediahub.pear.model.fromOtherSource
import com.mediahub.pear.model.blocksReplacement

/** The title a source belongs to: a film, or one episode of a series. What a download records about it. */
class SourceOwner(
    val imdb: String,
    val item: MetaPreview,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val thumb: String?,
    val logo: String?,
    val runtime: Int?,
) {
    /** The download id of this title or episode. */
    val key: String get() = downloadKey(imdb, season, episode)
}

/** Queues the download of [stream]. The context menu and the small download button both start downloads this way. */
fun startSourceDownload(env: AppEnv, owner: SourceOwner, addon: Addon, stream: StreamItem) {
    if (stream.downloadUrl == null) return
    env.data.downloads.enqueue(
        newDownload(
            imdb = owner.imdb,
            title = owner.item.name,
            season = owner.season,
            episode = owner.episode,
            episodeTitle = owner.episodeTitle,
            thumb = owner.thumb,
            logo = owner.logo,
            runtime = owner.runtime,
            addonID = addon.id,
            stream = stream,
        ),
    )
}

/**
 * A source's menu (the long press on the iOS source row, DetailView.swift): the download, or what the listed download
 * can do, and pin or unpin the source.
 */
fun sourceMenuItems(env: AppEnv, owner: SourceOwner, addon: Addon, stream: StreamItem, pinned: Boolean): List<ContextMenuItem> {
    val downloads = env.data.downloads
    val link = stream.downloadUrl
    val mine = downloads.records.value.forSource(owner.key, link)
    val other = downloads.records.value.fromOtherSource(owner.key, link)
    val items = mutableListOf<ContextMenuItem>()
    when {
        mine != null -> {
            items += downloadActions(downloads, mine)
        }
        link == null -> {
            val label = if (stream.isTorrent) "Torrents can't be downloaded" else "This link can't be downloaded"
            items += ContextMenuItem(label, "nosign") { }
        }
        other?.state?.blocksReplacement == true -> {
            items += ContextMenuItem("Another source is downloading this title", "nosign") { }
        }
        else -> {
            items += ContextMenuItem("Download", "arrow.down.circle") { startSourceDownload(env, owner, addon, stream) }
        }
    }
    if (pinned) {
        items += ContextMenuItem("Unpin Source", "pin.slash") { env.data.pinned.remove(owner.imdb) }
    } else {
        items += ContextMenuItem("Pin Source", "pin") { env.data.pinned.set(Pin(addon.id, stream.signature), owner.imdb) }
    }
    return items
}

/**
 * The actions a listed download offers, as its menu and the Downloads list show them. [onPlay] adds Play first to a finished
 * download's menu; a download with no file on disk gets no [onPlay].
 */
fun downloadActions(
    downloads: DownloadManager,
    record: DownloadRecord,
    onPlay: ((DownloadRecord) -> Unit)? = null,
): List<ContextMenuItem> {
    val cancel = ContextMenuItem("Cancel Download", "xmark.circle", destructive = true) { downloads.delete(record.id) }
    return when (record.state) {
        DownloadState.Done -> listOfNotNull(
            onPlay?.let { play -> ContextMenuItem("Play", "play.fill") { play(record) } },
            ContextMenuItem("Delete Download", "trash", destructive = true) { downloads.delete(record.id) },
        )
        DownloadState.Queued, DownloadState.Downloading -> listOf(
            ContextMenuItem("Pause Download", "pause.circle") { downloads.pause(record.id) },
            cancel,
        )
        DownloadState.Paused -> listOf(ContextMenuItem("Resume Download", "play.circle") { downloads.resume(record.id) }, cancel)
        DownloadState.Failed -> listOf(ContextMenuItem("Retry Download", "arrow.clockwise") { downloads.retry(record.id) }, cancel)
    }
}

/**
 * An episode's menu (the "..." button and the right click on an episode card, DetailView.swift): the download, which
 * starts from the sources list, and the watched marks.
 */
fun episodeMenuItems(
    env: AppEnv,
    item: MetaPreview,
    imdb: String,
    season: Int,
    ep: EpisodeItem,
    onChooseSource: () -> Unit,
): List<ContextMenuItem> {
    val history = env.data.history
    val downloads = env.data.downloads
    val record = if (imdb.isEmpty()) null else downloads.record(downloadKey(imdb, season, ep.id))
    val seconds = (ep.runtime ?: 45).toDouble() * 60.0
    val items = mutableListOf<ContextMenuItem>()
    if (record != null) {
        items += downloadActions(downloads, record)
    } else {
        items += ContextMenuItem("Download…", "arrow.down.circle") { onChooseSource() }
    }
    items += ContextMenuItem("Mark as Watched", "checkmark.circle") {
        history.markWatched(
            item,
            key = "$season:${ep.id}",
            season = season,
            episode = ep.id,
            duration = seconds,
            episodeTitle = ep.name,
            thumb = ep.image,
        )
    }
    items += ContextMenuItem("Mark Episodes Until Here as Watched", "checkmark.circle.fill") {
        history.markThrough(episode = ep.id, season = season, item = item, duration = seconds, episodeTitle = ep.name, thumb = ep.image)
    }
    items += ContextMenuItem("Unmark as Watched", "circle", destructive = true) {
        history.unmarkThrough(episode = ep.id, season = season, item = item)
    }
    return items
}

/**
 * The small download button on a source: it starts the download, then pauses, resumes or retries it, and shows a check
 * once the file is on disk. Only direct links have one (torrents cannot be downloaded).
 */
@Composable
fun SourceDownloadButton(env: AppEnv, owner: SourceOwner, addon: Addon, stream: StreamItem) {
    val downloads = env.data.downloads
    val records by downloads.records.collectAsState()
    val id = owner.key
    val link = stream.downloadUrl
    // This row's own download: the record of the title from this row's link. Another source's record is not this row's.
    val mine = records.forSource(id, link)?.state
    // Another source of the title is queued, running or done: this row waits until that download is deleted.
    val blocked = mine == null && records.fromOtherSource(id, link)?.state?.blocksReplacement == true
    val symbol = when (mine) {
        null -> "arrow.down.circle"
        DownloadState.Queued, DownloadState.Downloading -> "pause.circle"
        DownloadState.Paused -> "play.circle"
        DownloadState.Failed -> "arrow.clockwise"
        DownloadState.Done -> "checkmark.circle.fill"
    }
    val tint = if (mine == null || mine == DownloadState.Failed) null else PearTheme.colors.accent
    GlassButton(
        onClick = {
            when (mine) {
                null -> startSourceDownload(env, owner, addon, stream)
                DownloadState.Queued, DownloadState.Downloading -> downloads.pause(id)
                DownloadState.Paused -> downloads.resume(id)
                DownloadState.Failed -> downloads.retry(id)
                DownloadState.Done -> Unit
            }
        },
        symbol = symbol,
        size = 34.dp,
        tint = tint,
        enabled = !blocked,
    )
}

/**
 * What a download's state reads as: under its source, and in the Downloads list. [speed] is bytes per second, 0 when it is
 * not measured. While a download runs, the speed shows when there is one, and the time left when the size is known too.
 */
fun downloadStatusText(r: DownloadRecord, speed: Long = 0L): String = when (r.state) {
    DownloadState.Queued -> "Queued"
    DownloadState.Downloading -> {
        val parts = mutableListOf("Downloading · ${progressText(r)}")
        if (speed > 0) parts += speedText(speed)
        if (r.expectedBytes > 0 && speed > 0) parts += etaText((r.expectedBytes - r.bytes).coerceAtLeast(0L) / speed)
        parts.joinToString(" · ")
    }
    DownloadState.Paused -> "Paused · ${progressText(r)}"
    DownloadState.Failed -> "Download failed" + (r.error?.let { " · $it" } ?: "")
    DownloadState.Done -> "Downloaded · ${StreamSize.format(r.bytes)}"
}

private fun progressText(r: DownloadRecord): String =
    if (r.expectedBytes > 0) "${(r.fraction * 100).toInt()}% of ${StreamSize.format(r.expectedBytes)}" else StreamSize.format(r.bytes)

/** A thin bar showing how much of a download is on disk. */
@Composable
fun DownloadBar(fraction: Double) {
    val c = PearTheme.colors
    Box(Modifier.fillMaxWidth().height(3.dp).background(c.fill)) {
        Box(Modifier.fillMaxWidth(fraction.toFloat()).fillMaxHeight().background(c.accent))
    }
}

/** The status of a source's download, and its progress bar while the file is still coming. Nothing when it has none. */
@Composable
fun SourceDownloadStatus(env: AppEnv, owner: SourceOwner, link: String?) {
    val records by env.data.downloads.records.collectAsState()
    val speeds by env.data.downloads.speeds.collectAsState()
    val r = records.forSource(owner.key, link) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PearText(
            downloadStatusText(r, speeds[r.id] ?: 0L),
            style = PearTheme.type.caption,
            color = if (r.state == DownloadState.Failed) PearTheme.colors.red else PearTheme.colors.textSecondary,
            maxLines = 1,
        )
        if (r.state != DownloadState.Done && r.state != DownloadState.Failed) DownloadBar(r.fraction)
    }
}

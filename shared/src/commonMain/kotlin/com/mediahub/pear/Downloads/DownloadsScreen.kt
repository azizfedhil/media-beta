package com.mediahub.pear.downloads

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.mediahub.pear.detail.downloadActions
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.detail.DownloadBar
import com.mediahub.pear.detail.downloadStatusText
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.model.DownloadRecord
import com.mediahub.pear.model.DownloadState
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.model.StreamSize
import com.mediahub.pear.model.speedText
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/**
 * The downloads: every title or episode with its state, a progress bar while its file is still coming, and the buttons to
 * pause, resume, retry, play or delete it. Newest first. [onPlay] gets the request for a finished download's file.
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val bottomInset = LocalBottomInset.current
    val records by env.data.downloads.records.collectAsState()
    val speeds by env.data.downloads.speeds.collectAsState()
    val rows = records.sortedByDescending { it.added }
    // A finished download plays its file. A file that is no longer on disk plays nothing.
    val playRecord: (DownloadRecord) -> Unit = { r ->
        env.data.downloads.localPath(r)?.let { path -> onPlay(downloadPlayRequest(env, r, path)) }
    }

    Box(modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize()
                    .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearIcon("arrow.down.circle", size = 48.dp, tint = PearTheme.colors.textTertiary)
                    PearText("No downloads", style = PearTheme.type.headline)
                    PearText(
                        "Download a source from its menu or its button. Titles you download will appear here.",
                        style = PearTheme.type.subheadline,
                        color = PearTheme.colors.textSecondary,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The total speed, while anything is coming in: the sum of every running download's speed.
                val totalSpeed = speeds.values.sum()
                val downloading = rows.any { it.state == DownloadState.Downloading }
                val header = "${StreamSize.format(env.data.downloads.usedBytes())} on this device" +
                    if (downloading && totalSpeed > 0) " · ${speedText(totalSpeed)}" else ""
                PearText(
                    header,
                    style = PearTheme.type.caption,
                    color = PearTheme.colors.textSecondary,
                )
                rows.forEach { key(it.id) { DownloadRow(it, env, speeds[it.id] ?: 0L, playRecord) } }
            }
        }

        GlassTopBar(title = "Downloads", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

@Composable
private fun DownloadRow(r: DownloadRecord, env: AppEnv, speed: Long, onPlay: (DownloadRecord) -> Unit) {
    val downloads = env.data.downloads
    // The file of a finished download, when it is still on disk. Null while the download is not done, or when the file is gone.
    val localPath = remember(r.id, r.state) { if (r.state == DownloadState.Done) downloads.localPath(r) else null }
    // Tapping the text toggles the download: pause while it runs, resume when paused, retry when failed, play when done.
    fun tapText() {
        when (r.state) {
            DownloadState.Queued, DownloadState.Downloading -> downloads.pause(r.id)
            DownloadState.Paused -> downloads.resume(r.id)
            DownloadState.Failed -> downloads.retry(r.id)
            DownloadState.Done -> if (localPath != null) onPlay(r)
        }
    }
    // A right click (or a long press) opens the row's menu, as the iOS row does: pause, resume, retry, play, delete.
    val anchor = remember { MenuAnchor() }
    GlassCard(
        Modifier.fillMaxWidth().menuAnchor(anchor).clickOrMenu(null, onClick = {}, onMenu = {
            env.shell.openContextMenu(
                ContextMenuRequest(anchor.rect(), downloadActions(downloads, r, onPlay = if (localPath != null) onPlay else null)),
            )
        }),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The text is the row's own tap target. It is a sibling of the buttons, so their taps never reach it.
            Column(Modifier.weight(1f).clickable { tapText() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PearText(r.title, style = PearTheme.type.headline, maxLines = 1)
                if (r.isEpisode) {
                    val episode = "S${r.season} · E${r.episode}" + (r.episodeTitle?.let { " · $it" } ?: "")
                    PearText(episode, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 1)
                }
                PearText(
                    downloadStatusText(r, speed),
                    style = PearTheme.type.caption,
                    color = if (r.state == DownloadState.Failed) PearTheme.colors.red else PearTheme.colors.textSecondary,
                    maxLines = 2,
                )
                if (r.state == DownloadState.Done && localPath == null) {
                    PearText(
                        "The file is missing from this device.",
                        style = PearTheme.type.caption,
                        color = PearTheme.colors.red,
                        maxLines = 2,
                    )
                }
                if (r.state != DownloadState.Done && r.state != DownloadState.Failed) DownloadBar(r.fraction)
            }
            when (r.state) {
                DownloadState.Queued, DownloadState.Downloading ->
                    GlassButton(onClick = { downloads.pause(r.id) }, symbol = "pause.circle", size = 40.dp, tint = PearTheme.colors.accent)
                DownloadState.Paused ->
                    GlassButton(onClick = { downloads.resume(r.id) }, symbol = "play.circle", size = 40.dp, tint = PearTheme.colors.accent)
                DownloadState.Failed ->
                    GlassButton(onClick = { downloads.retry(r.id) }, symbol = "arrow.clockwise", size = 36.dp)
                DownloadState.Done ->
                    if (localPath != null) GlassButton(onClick = { onPlay(r) }, symbol = "play.fill", size = 36.dp, tint = PearTheme.colors.accent)
            }
            GlassButton(onClick = { downloads.delete(r.id) }, symbol = "trash", size = 36.dp)
        }
    }
}

/**
 * The request the player gets for a finished download: its file on disk, under the title it belongs to. The title is the one
 * the library or the history knows, so the player shows the same name and art; otherwise it is the record's own.
 */
private fun downloadPlayRequest(env: AppEnv, record: DownloadRecord, path: String): PlayRequest {
    val known: MetaPreview? = env.data.history.entry(record.imdb)?.item ?: env.data.library.entry(record.imdb)?.item
    return PlayRequest(
        url = path,
        headers = emptyMap(),
        item = known ?: MetaPreview(
            id = record.imdb,
            type = if (record.isEpisode) "series" else "movie",
            name = record.title,
            logo = record.logo,
        ),
        key = if (record.season != null) "${record.season}:${record.episode}" else "movie",
        imdb = record.imdb,
        season = record.season,
        episode = record.episode,
        episodeTitle = record.episodeTitle,
        logo = record.logo,
        thumb = record.thumb,
        p2p = null,
        sourceAddonID = record.addonID,
        sourceSignature = record.signature,
    )
}

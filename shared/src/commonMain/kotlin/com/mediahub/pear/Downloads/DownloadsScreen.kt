package com.mediahub.pear.downloads

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
import com.mediahub.pear.model.StreamSize
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/**
 * The downloads: every title or episode with its state, a progress bar while its file is still coming, and the buttons to
 * pause, resume, retry or delete it. Newest first.
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val bottomInset = LocalBottomInset.current
    val records by env.data.downloads.records.collectAsState()
    val rows = records.sortedByDescending { it.added }

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
                PearText(
                    "${StreamSize.format(env.data.downloads.usedBytes())} on this device",
                    style = PearTheme.type.caption,
                    color = PearTheme.colors.textSecondary,
                )
                rows.forEach { DownloadRow(it, env) }
            }
        }

        GlassTopBar(title = "Downloads", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

@Composable
private fun DownloadRow(r: DownloadRecord, env: AppEnv) {
    val downloads = env.data.downloads
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PearText(r.title, style = PearTheme.type.headline, maxLines = 1)
                if (r.isEpisode) {
                    val episode = "S${r.season} · E${r.episode}" + (r.episodeTitle?.let { " · $it" } ?: "")
                    PearText(episode, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary, maxLines = 1)
                }
                PearText(
                    downloadStatusText(r),
                    style = PearTheme.type.caption,
                    color = if (r.state == DownloadState.Failed) PearTheme.colors.red else PearTheme.colors.textSecondary,
                    maxLines = 2,
                )
                if (r.state != DownloadState.Done && r.state != DownloadState.Failed) DownloadBar(r.fraction)
            }
            when (r.state) {
                DownloadState.Queued, DownloadState.Downloading ->
                    GlassButton(onClick = { downloads.pause(r.id) }, symbol = "pause.circle", size = 36.dp)
                DownloadState.Paused ->
                    GlassButton(onClick = { downloads.resume(r.id) }, symbol = "play.circle", size = 36.dp)
                DownloadState.Failed ->
                    GlassButton(onClick = { downloads.retry(r.id) }, symbol = "arrow.clockwise", size = 36.dp)
                DownloadState.Done -> Unit
            }
            GlassButton(onClick = { downloads.delete(r.id) }, symbol = "trash", size = 36.dp)
        }
    }
}

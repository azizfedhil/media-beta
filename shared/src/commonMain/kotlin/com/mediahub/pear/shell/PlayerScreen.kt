package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/**
 * Batch 4.1 stand-in: loads the chosen source into the engine and shows state, position and errors.
 * The real overlay is Batch 4.3, the video surface Batch 4.2.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember(request) { createDefaultPlayerEngine() }
    DisposableEffect(engine) { onDispose { engine.release() } }

    val state by engine.state.collectAsState()
    val pos by engine.positionSeconds.collectAsState()
    val dur by engine.durationSeconds.collectAsState()
    val buf by engine.bufferedSeconds.collectAsState()
    val error by engine.errorMessage.collectAsState()

    val isP2P = request.url.startsWith("p2p://")
    LaunchedEffect(engine) {
        if (!isP2P) engine.load(request.url, request.headers, startPositionSeconds = 0.0)
    }

    val title = buildString {
        append(request.item.name)
        if (request.season != null && request.episode != null) append("  ·  S${request.season}E${request.episode}")
        request.episodeTitle?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
    }
    val shownError = if (isP2P) "Peer-to-peer sources aren't supported on desktop yet (Batch 4.5). Pick a direct or debrid source." else error

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val label = when {
                shownError != null -> "Can't play this source"
                state == PlaybackState.Loading || state == PlaybackState.Idle -> "Loading…"
                state == PlaybackState.Buffering -> "Buffering…"
                state == PlaybackState.Paused -> "Paused"
                state == PlaybackState.Ended -> "Finished"
                else -> "Playing"
            }
            PearText(label, style = PearTheme.type.title3, color = if (shownError != null) PearTheme.colors.red else PearTheme.colors.textPrimary)
            shownError?.let { PearText(it, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary) }

            if (shownError == null) {
                // Seek bar: tap to jump.
                val frac = if (dur > 0) (pos / dur).coerceIn(0.0, 1.0).toFloat() else 0f
                val bufFrac = if (dur > 0) (buf / dur).coerceIn(0.0, 1.0).toFloat() else 0f
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .pointerInput(dur) {
                            detectTapGestures { offset ->
                                if (dur > 0) engine.seek(dur * (offset.x / size.width))
                            }
                        },
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(bufFrac).background(Color.White.copy(alpha = 0.25f)))
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(PearTheme.colors.accent))
                }
                PearText("${Fmt.clock(pos)} / ${Fmt.clock(dur)}", style = PearTheme.type.subheadline)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(onClick = { engine.seek(maxOf(0.0, pos - 10.0)) }, label = "-10s")
                    GlassButton(
                        onClick = { engine.togglePlay() },
                        symbol = if (state == PlaybackState.Paused) "play.fill" else "pause.fill",
                        tint = PearTheme.colors.accent,
                    )
                    GlassButton(onClick = { engine.seek(if (dur > 0) minOf(dur, pos + 10.0) else pos + 10.0) }, label = "+10s")
                }
            }
        }
        GlassTopBar(title = title, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

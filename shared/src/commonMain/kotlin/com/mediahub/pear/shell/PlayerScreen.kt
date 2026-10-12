package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.VideoSurface
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.delay

/**
 * Batch 4.2: the video is drawn in the window (see [VideoSurface]) with a plain control bar on top.
 * The real overlay (pill, skip intro, autoplay next) is Batch 4.3.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember(request) { createDefaultPlayerEngine() }
    DisposableEffect(engine) { onDispose { engine.release() } }

    val window = LocalWindowControl.current
    var fullscreen by remember { mutableStateOf(false) }
    fun setFullscreen(value: Boolean) {
        fullscreen = value
        window?.isFullscreen = value
    }
    DisposableEffect(Unit) { onDispose { window?.isFullscreen = false } }

    var aspect by remember { mutableStateOf(AspectMode.Fit) }
    var performance by remember { mutableStateOf(false) }
    LaunchedEffect(engine, aspect) { engine.setAspectMode(aspect) }

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

    // Controls fade out after 3 s without mouse movement, but stay while paused, loading or failed.
    var activity by remember { mutableIntStateOf(0) }
    var recentlyActive by remember { mutableStateOf(true) }
    LaunchedEffect(activity) {
        recentlyActive = true
        delay(3000)
        recentlyActive = false
    }
    val showControls = recentlyActive || shownError != null || state == PlaybackState.Paused ||
        state == PlaybackState.Loading || state == PlaybackState.Idle || state == PlaybackState.Ended

    Box(
        modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    activity++
                }
            }
        },
    ) {
        if (!isP2P) {
            VideoSurface(
                engine = engine,
                modifier = Modifier.fillMaxSize(),
                performanceMode = performance,
                onDoubleClick = { setFullscreen(!fullscreen) },
            )
        }

        val label = when {
            shownError != null -> "Can't play this source"
            state == PlaybackState.Loading || state == PlaybackState.Idle -> "Loading…"
            state == PlaybackState.Buffering -> "Buffering…"
            else -> null
        }
        if (label != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PearText(label, style = PearTheme.type.title3, color = if (shownError != null) PearTheme.colors.red else Color.White)
                shownError?.let { PearText(it, style = PearTheme.type.footnote, color = Color.White.copy(alpha = 0.7f)) }
            }
        }

        if (showControls) {
            GlassTopBar(title = title, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)

            if (shownError == null) {
                GlassCard(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                    SeekBar(
                        position = pos,
                        duration = dur,
                        buffered = buf,
                        onSeek = { engine.seek(it) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassButton(onClick = { engine.seek(maxOf(0.0, pos - 10.0)) }, label = "-10s")
                        GlassButton(
                            onClick = { engine.togglePlay() },
                            symbol = if (state == PlaybackState.Paused || state == PlaybackState.Ended) "play.fill" else "pause.fill",
                            tint = PearTheme.colors.accent,
                        )
                        GlassButton(onClick = { engine.seek(if (dur > 0) minOf(dur, pos + 10.0) else pos + 10.0) }, label = "+10s")
                        PearText("${Fmt.clock(pos)} / ${Fmt.clock(dur)}", style = PearTheme.type.subheadline, color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        GlassButton(onClick = { aspect = AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size] }, label = aspect.name)
                        GlassButton(
                            onClick = { performance = !performance },
                            label = if (performance) "4K: fast" else "4K: full",
                        )
                        GlassButton(onClick = { setFullscreen(!fullscreen) }, label = if (fullscreen) "Exit full" else "Full")
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(position: Double, duration: Double, buffered: Double, onSeek: (Double) -> Unit) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val played = if (duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val bufferedFrac = if (duration > 0) (buffered / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(
        Modifier.fillMaxWidth().height(18.dp)
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek(duration * (offset.x / size.width)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> scrub = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        scrub?.let { if (duration > 0) onSeek(duration * it) }
                        scrub = null
                    },
                    onDragCancel = { scrub = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(bufferedFrac).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(scrub ?: played).background(PearTheme.colors.accent))
        }
    }
}

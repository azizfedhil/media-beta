package com.mediahub.pear.shell

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.TrackKind
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

@Composable
fun PlayerCheckScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val engine = remember { createDefaultPlayerEngine() }
    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    val state by engine.state.collectAsState()
    val pos by engine.positionSeconds.collectAsState()
    val dur by engine.durationSeconds.collectAsState()
    val buf by engine.bufferedSeconds.collectAsState()
    val rate by engine.playbackRate.collectAsState()
    val volume by engine.volume.collectAsState()
    val error by engine.errorMessage.collectAsState()
    val audioTracks by engine.audioTracks.collectAsState()
    val subtitleTracks by engine.subtitleTracks.collectAsState()

    var testUrl by remember { mutableStateOf("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") }
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Player Engine State Overview
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("libmpv Engine State", style = PearTheme.type.headline)
                PearText("Status: ${state.name}", style = PearTheme.type.title3.weight(FontWeight.Bold), color = if (state == PlaybackState.Playing) PearTheme.colors.accent else PearTheme.colors.textPrimary)
                PearText("Position: ${Fmt.clock(pos)} / ${Fmt.clock(dur)}  (Buffered: ${Fmt.clock(buf)})", style = PearTheme.type.subheadline)
                PearText("Speed: ${rate}x  ·  Volume: ${(volume * 100).toInt()}%", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)

                error?.let {
                    PearText("Error: $it", style = PearTheme.type.footnote, color = PearTheme.colors.red)
                }
            }

            // Stream URL Loader
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Load test stream", style = PearTheme.type.headline)
                TextInput(testUrl, { testUrl = it }, "Media stream URL")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        onClick = {
                            scope.launch {
                                engine.load(testUrl.trim(), startPositionSeconds = 0.0)
                            }
                        },
                        label = "Load & Play",
                        tint = PearTheme.colors.accent,
                    )
                    GlassButton(
                        onClick = { engine.release() },
                        label = "Stop / Reset",
                    )
                }
            }

            // Transport Controls
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Transport controls", style = PearTheme.type.headline)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = { engine.play() }, label = "Play", symbol = "play.fill")
                    GlassButton(onClick = { engine.pause() }, label = "Pause", symbol = "pause.fill")
                    GlassButton(onClick = { engine.togglePlay() }, label = "Toggle", symbol = "playpause.fill")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevChip("-10s", false) { engine.seek(maxOf(0.0, pos - 10.0)) }
                    DevChip("+10s", false) { engine.seek(minOf(dur, pos + 10.0)) }
                    DevChip("1.0x", rate == 1.0) { engine.setRate(1.0) }
                    DevChip("1.5x", rate == 1.5) { engine.setRate(1.5) }
                    DevChip("2.0x", rate == 2.0) { engine.setRate(2.0) }
                }
            }

            // Audio Tracks
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Audio tracks (${audioTracks.size})", style = PearTheme.type.headline)
                if (audioTracks.isEmpty()) {
                    PearText("No audio tracks found or file not loaded yet.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                } else {
                    audioTracks.forEach { track ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PearText("${track.title} [${track.language ?: "unknown"}]", style = PearTheme.type.subheadline)
                            DevChip(if (track.isSelected) "Active" else "Select", track.isSelected) {
                                engine.selectTrack(TrackKind.Audio, track.id)
                            }
                        }
                    }
                }
            }

            // Subtitle Tracks
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Subtitle tracks (${subtitleTracks.size})", style = PearTheme.type.headline)
                if (subtitleTracks.isEmpty()) {
                    PearText("No subtitle tracks found or file not loaded yet.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PearText("Disable subtitles", style = PearTheme.type.subheadline)
                        DevChip("Off", subtitleTracks.none { it.isSelected }) {
                            engine.selectTrack(TrackKind.Subtitle, null)
                        }
                    }
                    subtitleTracks.forEach { track ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PearText("${track.title} [${track.language ?: "unknown"}]", style = PearTheme.type.subheadline)
                            DevChip(if (track.isSelected) "Active" else "Select", track.isSelected) {
                                engine.selectTrack(TrackKind.Subtitle, track.id)
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(title = "Player check", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}
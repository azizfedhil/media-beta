package com.mediahub.pear.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart

/**
 * The player's overlay, one for every platform (Batch 4.3a). Layout and behaviour follow `PlayerScreen` in
 * PlayerView.swift:
 *
 * - top: a round close button (the title is not up here; it sits above the seek bar, as on iOS)
 * - centre: skip back 10 s, play/pause, skip forward 10 s; a spinner on the play button while waiting
 * - bottom: title and episode line on the left, the option pill on the right, the seek bar underneath
 * - fades out 3.5 s after the last mouse movement while playing, and the cursor goes with it; stays while
 *   paused, loading, ended or failed, while dragging the seek bar, while hovering the controls
 * - a click on the video toggles play/pause, a double-click toggles fullscreen
 *
 * The video goes in [video]: it is the blur source for the glass surfaces, so blur works over live frames.
 * [look] decides how surfaces are drawn (see [playerLook]). [actions] fills the right end of the pill; 4.3c
 * replaces it with the pinned/ordered options.
 *
 * Only the seek bar and the volume slider read the engine's clock and volume, so the rest of the overlay
 * does not recompose while a video plays.
 */
@Composable
fun PlayerOverlay(
    engine: PlayerEngine,
    title: String,
    subtitle: String?,
    look: PlayerLook,
    glass: GlassStore,
    errorMessage: String?,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    video: @Composable BoxScope.() -> Unit,
) {
    val state by engine.state.collectAsState()

    // Mouse activity: every pointer event pings, the countdown restarts on each ping.
    val pings = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    var recentlyActive by remember { mutableStateOf(true) }
    LaunchedEffect(pings) {
        pings.onStart { emit(Unit) }.collectLatest {
            recentlyActive = true
            delay(AUTO_HIDE_MILLIS)
            recentlyActive = false
        }
    }
    // Play, pause, loading and so on start a fresh countdown, so resuming does not hide the controls at once.
    LaunchedEffect(state) { pings.tryEmit(Unit) }

    var scrubbing by remember { mutableStateOf(false) }
    var volumeActive by remember { mutableStateOf(false) }
    val overTop = remember { mutableStateOf(false) }
    val overCenter = remember { mutableStateOf(false) }
    val overBottom = remember { mutableStateOf(false) }

    val visible = controlsVisible(
        state = state,
        hasError = errorMessage != null,
        recentlyActive = recentlyActive,
        scrubbing = scrubbing,
        pointerOverControls = overTop.value || overCenter.value || overBottom.value,
        volumeActive = volumeActive,
    )

    val hiddenCursor = remember { hiddenPointerIcon() }

    val playPause: () -> Unit = {
        if (engine.state.value == PlaybackState.Ended) {
            engine.seek(0.0)
            engine.play()
        } else {
            engine.togglePlay()
        }
    }
    val onTap by rememberUpdatedState(playPause)
    val onDoubleTap by rememberUpdatedState(onToggleFullscreen)

    GlassScene(
        store = glass,
        modifier = modifier.fillMaxSize().background(Color.Black)
            .pointerHoverIcon(if (visible) PointerIcon.Default else hiddenCursor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        pings.tryEmit(Unit)
                    }
                }
            },
        background = video,
    ) {
        CompositionLocalProvider(LocalPlayerLook provides look) {
            // Click and double-click on the picture. The controls sit above and take their own clicks.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() })
                },
            )

            // Paused: one flat dim over the frozen frame (a real blur of the frame would cost far more).
            AnimatedVisibility(
                visible = state == PlaybackState.Paused && errorMessage == null && visible,
                enter = fadeIn(tween(PearMotion.BASE)),
                exit = fadeOut(tween(PearMotion.BASE)),
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.6f)))),
                )
            }

            // Waiting for data while the controls are hidden: just a spinner.
            if (isWaiting(state) && errorMessage == null && !visible) {
                PlayerSpinner(Modifier.align(Alignment.Center), diameter = 44.dp)
            }

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(PearMotion.BASE)),
                exit = fadeOut(tween(PearMotion.BASE)),
            ) {
                Controls(
                    engine = engine,
                    state = state,
                    title = title,
                    subtitle = subtitle,
                    errorMessage = errorMessage,
                    onClose = onClose,
                    onPlayPause = playPause,
                    actions = actions,
                    overTop = overTop,
                    overCenter = overCenter,
                    overBottom = overBottom,
                    onScrubbing = { scrubbing = it },
                    onVolumeActive = { volumeActive = it },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    engine: PlayerEngine,
    state: PlaybackState,
    title: String,
    subtitle: String?,
    errorMessage: String?,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    overTop: MutableState<Boolean>,
    overCenter: MutableState<Boolean>,
    overBottom: MutableState<Boolean>,
    onScrubbing: (Boolean) -> Unit,
    onVolumeActive: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Scrims behind the controls; they take no input.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(100.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
        }

        Box(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 16.dp).hoverFlag(overTop)) {
            PlayerCircleButton("xmark", size = 42.dp, icon = 16.dp, onClick = onClose, contentDescription = "Close")
        }

        if (errorMessage != null) {
            ErrorCard(errorMessage, onClose, Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            Transport(engine, state, onPlayPause, Modifier.align(Alignment.Center).hoverFlag(overCenter))
            BottomBar(
                engine = engine,
                title = title,
                subtitle = subtitle,
                actions = actions,
                onScrubbing = onScrubbing,
                onVolumeActive = onVolumeActive,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 14.dp).hoverFlag(overBottom),
            )
        }
    }
}

/** Skip back, play/pause, skip forward (`transport` in PlayerView.swift: 50 / 68 / 50 pt, 38 pt apart). */
@Composable
private fun Transport(engine: PlayerEngine, state: PlaybackState, onPlayPause: () -> Unit, modifier: Modifier = Modifier) {
    val waiting = isWaiting(state)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(38.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerCircleButton(
            "gobackward.10", size = 50.dp, icon = 21.dp, contentDescription = "Back 10 seconds",
            onClick = { engine.seek(SeekMath.skipped(engine.positionSeconds.value, -10.0, engine.durationSeconds.value)) },
        )
        Box(contentAlignment = Alignment.Center) {
            PlayerCircleButton(
                if (showsPauseSymbol(state)) "pause.fill" else "play.fill",
                size = 68.dp, icon = 28.dp,
                onClick = onPlayPause,
                modifier = Modifier.graphicsLayer { alpha = if (waiting) 0.3f else 1f },
                contentDescription = if (showsPauseSymbol(state)) "Pause" else "Play",
            )
            if (waiting) PlayerSpinner(diameter = 36.dp)
        }
        PlayerCircleButton(
            "goforward.10", size = 50.dp, icon = 21.dp, contentDescription = "Forward 10 seconds",
            onClick = { engine.seek(SeekMath.skipped(engine.positionSeconds.value, 10.0, engine.durationSeconds.value)) },
        )
    }
}

/**
 * Title and episode line on the left, the option pill on the right, the seek bar at the very bottom.
 * In a narrow window the pill drops below the text (`ViewThatFits` in Swift).
 */
@Composable
private fun BottomBar(
    engine: PlayerEngine,
    title: String,
    subtitle: String?,
    actions: @Composable RowScope.() -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onVolumeActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 620.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    TitleBlock(title, subtitle, Modifier.weight(1f))
                    OptionPill(engine, actions, onVolumeActive)
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleBlock(title, subtitle, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OptionPill(engine, actions, onVolumeActive) }
                }
            }
        }
        PlayerSeekBar(engine, onScrubbing)
    }
}

@Composable
private fun OptionPill(engine: PlayerEngine, actions: @Composable RowScope.() -> Unit, onVolumeActive: (Boolean) -> Unit) {
    PlayerPill {
        PlayerVolumeControl(engine, onVolumeActive)
        actions()
    }
}

@Composable
private fun TitleBlock(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PearText(
            title,
            style = PearTheme.type.title2.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
        )
        if (subtitle != null) {
            PearText(
                subtitle,
                style = PearTheme.type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

/** `errorCard` in PlayerView.swift, without the iOS-only "Open in VLC / Infuse" buttons. */
@Composable
private fun ErrorCard(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    PlayerSurface(modifier.widthIn(max = 400.dp), SurfaceKind.Card) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PearIcon("exclamationmark.triangle.fill", size = 34.dp, tint = PearTheme.colors.yellow)
            PearText("Couldn't play this source", style = PearTheme.type.headline, color = Color.White, textAlign = TextAlign.Center)
            PearText(message, style = PearTheme.type.footnote, color = Color.White, textAlign = TextAlign.Center)
            PearText(
                "The link may have expired or the server may be refusing the request. Try another source.",
                style = PearTheme.type.caption,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            PlayerPill { PlayerPillButton(label = "Go back", onClick = onClose) }
        }
    }
}

/** Keeps [flag] true while the pointer is over this element. Feeds `pointerOverControls` in [controlsVisible]. */
@Composable
private fun Modifier.hoverFlag(flag: MutableState<Boolean>): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { flag.value = hovered }
    DisposableEffect(Unit) { onDispose { flag.value = false } }
    return hoverable(source)
}

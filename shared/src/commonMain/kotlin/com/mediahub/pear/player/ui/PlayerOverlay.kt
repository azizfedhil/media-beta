package com.mediahub.pear.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
 * The player's overlay, one for every platform (Batches 4.3a and 4.3c). Layout and behaviour follow `PlayerScreen`
 * in PlayerView.swift:
 *
 * - top: a round close button (the title sits above the seek bar, as on iOS)
 * - centre: skip back 10 s, play/pause, skip forward 10 s; a spinner on the play button while waiting
 * - bottom: title and episode line on the left (tap for the info card), the controls pill on the right, the seek bar
 *   underneath. Desktop-only buttons ([trailing]) sit after the pill.
 * - [panel] (speed, sources, info) floats over the video through [panels]; sources and info take the place of the
 *   controls, speed does not
 * - fades out 3.5 s after the last mouse movement while playing, and the cursor goes with it; stays while paused,
 *   loading, ended or failed, while dragging the seek bar, while hovering the controls, while the pill is open and
 *   while a panel is open
 * - a click on the video toggles play/pause, or closes an open panel; a double-click toggles fullscreen
 *
 * The video goes in [video]: it is the blur source for the glass surfaces, so blur works over live frames.
 * [look] decides how surfaces are drawn (see [playerLook]). [openedAt] is the resume point the source is opening at:
 * the seek bar shows it until the engine reports a position (iOS `holdPosition`).
 *
 * Only the seek bar reads the engine's clock, so the rest of the overlay does not recompose while a video plays.
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
    openedAt: Double? = null,
    panel: PlayerPanel? = null,
    onDismissPanel: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    pill: @Composable (onActive: (Boolean) -> Unit) -> Unit = {},
    trailing: @Composable () -> Unit = {},
    panels: @Composable BoxScope.() -> Unit = {},
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
    var pillActive by remember { mutableStateOf(false) }
    val overTop = remember { mutableStateOf(false) }
    val overCenter = remember { mutableStateOf(false) }
    val overBottom = remember { mutableStateOf(false) }

    val visible = controlsVisible(
        state = state,
        hasError = errorMessage != null,
        recentlyActive = recentlyActive,
        scrubbing = scrubbing,
        pointerOverControls = overTop.value || overCenter.value || overBottom.value,
        volumeActive = pillActive,
        panelOpen = panel != null,
    )
    val covered = panelCoversControls(panel)

    val hiddenCursor = remember { hiddenPointerIcon() }

    val playPause: () -> Unit = {
        if (engine.state.value == PlaybackState.Ended) {
            engine.seek(0.0)
            engine.play()
        } else {
            engine.togglePlay()
        }
    }
    // A click on the picture closes an open panel first, as tapBackground does on iOS.
    val tapAction: () -> Unit = { if (panel != null) onDismissPanel() else playPause() }
    val onTap by rememberUpdatedState(tapAction)
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
        // The controls and panels are drawn PLAYER_UI_SCALE larger, text included. The video behind them is not scaled.
        val base = LocalDensity.current
        val scaled = Density(base.density * PLAYER_UI_SCALE, base.fontScale * PLAYER_UI_SCALE)
        CompositionLocalProvider(LocalPlayerLook provides look, LocalDensity provides scaled) {
            // Click and double-click on the picture. The controls and panels sit above and take their own clicks.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() })
                },
            )

            // Paused: one flat dim over the frozen frame, not while a panel is open (`pausedDim` in PlayerView.swift).
            AnimatedVisibility(
                visible = state == PlaybackState.Paused && errorMessage == null && visible && panel == null,
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
                visible = visible && !covered,
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
                    onTitleClick = onTitleClick,
                    pill = { pill { pillActive = it } },
                    trailing = trailing,
                    overTop = overTop,
                    overCenter = overCenter,
                    overBottom = overBottom,
                    onScrubbing = { scrubbing = it },
                    openedAt = openedAt,
                )
            }

            // Panels last, so they sit above the controls. Only one is open at a time; the host decides which.
            panels()
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
    onTitleClick: () -> Unit,
    pill: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    overTop: MutableState<Boolean>,
    overCenter: MutableState<Boolean>,
    overBottom: MutableState<Boolean>,
    onScrubbing: (Boolean) -> Unit,
    openedAt: Double?,
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
                onTitleClick = onTitleClick,
                pill = pill,
                trailing = trailing,
                onScrubbing = onScrubbing,
                openedAt = openedAt,
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
 * Title and episode line on the left, the pill and the desktop buttons on the right, the seek bar at the very bottom.
 * In a narrow window the buttons drop below the text (`ViewThatFits` in Swift).
 */
@Composable
private fun BottomBar(
    engine: PlayerEngine,
    title: String,
    subtitle: String?,
    onTitleClick: () -> Unit,
    pill: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onScrubbing: (Boolean) -> Unit,
    openedAt: Double?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 620.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    TitleBlock(title, subtitle, onTitleClick, Modifier.weight(1f))
                    Buttons(pill, trailing)
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleBlock(title, subtitle, onTitleClick, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Buttons(pill, trailing) }
                }
            }
        }
        PlayerSeekBar(engine, onScrubbing, openedAt = openedAt)
    }
}

@Composable
private fun Buttons(pill: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        pill()
        trailing()
    }
}

/**
 * The series or movie name, with the episode line under it. The chevron says a tap opens the info card (the title
 * is the button, as on iOS).
 */
@Composable
private fun TitleBlock(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PearText(
                title,
                modifier = Modifier.weight(1f, fill = false),
                style = PearTheme.type.title2.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
            )
            PearIcon("chevron.up", size = 14.dp, tint = Color.White.copy(alpha = 0.85f))
        }
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

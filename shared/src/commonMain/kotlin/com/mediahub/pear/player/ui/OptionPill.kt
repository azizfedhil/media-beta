package com.mediahub.pear.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearMotion
import kotlinx.coroutines.delay

// Sizes of the pill, in dp (`pillSlot`, `pillGap`, `pillPad` in PlayerView.swift).
private const val SLOT_DP = 46f
private const val GAP_DP = 2f
private const val PAD_DP = 4f
private const val VOLUME_MIN_DP = 260f

/** Space kept between the pill and the window's left edge when it grows (dp, in the player's scale). */
private const val PILL_EDGE_MARGIN_DP = 12f

/** Seconds an unfolded pill stays open after the last tap. */
private const val PILL_LIFE_MILLIS = 5_000L

/** Seconds the volume slider stays after the last change (`bumpVolume` in PlayerView.swift). */
private const val VOLUME_LIFE_MILLIS = 3_000L

/**
 * Places the content against the right edge of the space it is given, and measures it without limits. When the content is
 * wider than that space it grows to the left, past it, and never to the right. The edge is placed exactly, rather than by
 * alignment, so a pill that is wider than the window keeps its right end (and its fullscreen button) on screen.
 */
private fun Modifier.anchorRight(): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(Constraints())
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
    val height = child.height.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { child.place(width - child.width, (height - child.height) / 2) }
}

/** Width of `slots` icons side by side. */
private fun span(slots: Int): Dp = if (slots <= 0) 0.dp else (SLOT_DP * slots + GAP_DP * (slots - 1)).dp

/**
 * The controls pill (`iconRow` in PlayerView.swift): the buttons of [layout] in one glass capsule. With more than four
 * buttons it folds to the pinned ones plus a chevron; the chevron unfolds the rest to the left, and the pill folds
 * itself again 5 s after the last tap. The speaker turns the pill into a volume slider, which goes back to the icons
 * 3 s after the last change.
 *
 * Widths come from the slot count (see [pillWindowSlots]), never from a measurement, so the glass, the icons and the
 * slider cannot disagree. The pill's layout footprint is always its folded size; anything wider grows to the left
 * over the title, so nothing around it moves.
 *
 * @param panelOpen a panel (speed, sources, info) is open: the pill folds back, as on iOS.
 * @param onActive true while the pill is open, unfolded or with the slider, and while the slider is dragged. The
 *   controls stay up while it is.
 */
@Composable
internal fun OptionPill(
    engine: PlayerEngine,
    layout: PillLayout,
    panelOpen: Boolean,
    speedOpen: Boolean,
    sourcesOpen: Boolean,
    displayOpen: Boolean,
    onSpeed: () -> Unit,
    onSources: () -> Unit,
    onDisplay: () -> Unit,
    subtitlesOpen: Boolean = false,
    /** A subtitle track is playing (the button shows as on, like the filled icon on iOS). */
    subtitlesOn: Boolean = false,
    onSubtitles: () -> Unit = {},
    audioOpen: Boolean = false,
    onAudio: () -> Unit = {},
    onNext: () -> Unit,
    nextEnabled: Boolean = true,
    fullscreen: Boolean = false,
    onFullscreen: () -> Unit = {},
    onActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rate by engine.playbackRate.collectAsState()
    val volume by engine.volume.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var editingVolume by remember { mutableStateOf(false) }
    var lastAudible by remember { mutableFloatStateOf(1f) }
    // Each bump restarts a countdown: a tap inside an unfolded pill, or a change of volume.
    var unfoldBumps by remember { mutableIntStateOf(0) }
    var volumeBumps by remember { mutableIntStateOf(0) }

    LaunchedEffect(volume) {
        if (volume > 0.001f) lastAudible = volume
        volumeBumps++
    }
    LaunchedEffect(panelOpen) {
        if (panelOpen) {
            expanded = false
            volumeOpen = false
        }
    }
    LaunchedEffect(expanded, unfoldBumps) {
        if (expanded) {
            delay(PILL_LIFE_MILLIS)
            expanded = false
        }
    }
    LaunchedEffect(volumeOpen, volumeBumps, editingVolume) {
        // Dragging the slider pauses the countdown: it only runs once the drag has ended.
        if (volumeOpen && !editingVolume) {
            delay(VOLUME_LIFE_MILLIS)
            volumeOpen = false
        }
    }
    val active = expanded || volumeOpen || editingVolume
    LaunchedEffect(active) { onActive(active) }
    DisposableEffect(Unit) { onDispose { onActive(false) } }

    val folded = layout.folded
    val mode = pillMode(expanded, volumeOpen, folded)
    // Full screen is always the pill's last slot, except in volume mode, where the slider takes the whole pill. It is
    // not one of the layout's items, so it never counts towards folding.
    val fullscreenSlots = if (mode == PillMode.Volume) 0 else 1
    val windowSlots = pillWindowSlots(layout, mode) + fullscreenSlots
    val desiredWidth = if (mode == PillMode.Volume) maxOf(span(windowSlots), VOLUME_MIN_DP.dp) else span(windowSlots)
    // The pill grows to the left, but never past the window's left edge. The room is measured from the footprint's
    // right edge after layout (in the player's dp). A narrow window then clips the far buttons instead of pushing the
    // pill off screen. It is not measured before the first layout, so the first frame shows the full width.
    var rightEdgeDp by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val density = LocalDensity.current
    val roomDp = (rightEdgeDp - PILL_EDGE_MARGIN_DP - PAD_DP * 2).coerceAtLeast(SLOT_DP)
    val windowWidth = minOf(desiredWidth, roomDp.dp)
    val animatedWidth by animateDpAsState(windowWidth, PearMotion.snappy<Dp>(PearMotion.BASE))

    val footprint = span(pillWindowSlots(layout, PillMode.Folded) + 1) + (PAD_DP * 2).dp
    val height = (SLOT_DP + PAD_DP * 2).dp

    fun bumpUnfolded() {
        if (expanded) unfoldBumps++
    }

    Box(
        modifier.width(footprint).height(height).onGloballyPositioned { coords ->
            // Distance from the window's left edge to the pill's right edge, in the player's dp.
            rightEdgeDp = density.run { (coords.positionInWindow().x + coords.size.width).toDp().value }
        },
        contentAlignment = Alignment.CenterEnd,
    ) {
        PlayerSurface(Modifier.anchorRight().requiredWidth(animatedWidth + (PAD_DP * 2).dp).height(height), SurfaceKind.Capsule) {
            Box(Modifier.fillMaxSize().padding(PAD_DP.dp).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                if (mode == PillMode.Volume) {
                    PlayerVolumeSliderRow(
                        level = volume,
                        onChange = { engine.setVolume(it) },
                        onToggleMute = { engine.setVolume(toggledMute(volume, lastAudible)) },
                        onEditing = { editingVolume = it },
                    )
                } else {
                    // Trailing, so the buttons that are folded away overflow to the left and are clipped. The
                    // unbounded wrap lets the row be wider than the window.
                    Row(
                        Modifier.anchorRight(),
                        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        layout.items.forEachIndexed { index, item ->
                            // Folded away: the slot keeps its place but is empty and takes no input.
                            val hidden = index < folded && mode != PillMode.Unfolded
                            Box(Modifier.size(SLOT_DP.dp), contentAlignment = Alignment.Center) {
                                if (!hidden) {
                                    PillButton(
                                        item = item,
                                        speedOpen = speedOpen,
                                        sourcesOpen = sourcesOpen,
                                        rate = rate,
                                        volume = volume,
                                        onSpeed = {
                                            bumpUnfolded()
                                            onSpeed()
                                        },
                                        onSources = {
                                            bumpUnfolded()
                                            onSources()
                                        },
                                        onVolume = {
                                            if (volumeOpen) {
                                                volumeOpen = false
                                            } else {
                                                expanded = false
                                                volumeOpen = true
                                            }
                                        },
                                        onNext = {
                                            bumpUnfolded()
                                            onNext()
                                        },
                                        nextEnabled = nextEnabled,
                                        displayOpen = displayOpen,
                                        onDisplay = {
                                            bumpUnfolded()
                                            onDisplay()
                                        },
                                        subtitlesOpen = subtitlesOpen,
                                        subtitlesOn = subtitlesOn,
                                        onSubtitles = {
                                            bumpUnfolded()
                                            onSubtitles()
                                        },
                                        audioOpen = audioOpen,
                                        onAudio = {
                                            bumpUnfolded()
                                            onAudio()
                                        },
                                    )
                                }
                            }
                        }
                        Box(Modifier.size(SLOT_DP.dp), contentAlignment = Alignment.Center) {
                            PlayerPillButton(
                                symbol = if (fullscreen) "arrow.down.right.and.arrow.up.left" else "arrow.up.left.and.arrow.down.right",
                                onClick = {
                                    bumpUnfolded()
                                    onFullscreen()
                                },
                                contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                            )
                        }
                    }
                }
                if (folded > 0 && mode != PillMode.Volume) {
                    // Left edge: unfolds the hidden buttons (the chevron points left), or folds them back (right).
                    Box(Modifier.align(Alignment.CenterStart)) {
                        PlayerPillButton(
                            symbol = if (expanded) "chevron.right" else "chevron.left",
                            onClick = {
                                if (expanded) {
                                    expanded = false
                                } else {
                                    expanded = true
                                    unfoldBumps++
                                }
                            },
                            contentDescription = if (expanded) "Fewer controls" else "More controls",
                        )
                    }
                }
            }
        }
    }
}

/**
 * One button of the pill. Only the buttons this build has are drawn: Next (4.3d), Subtitles and Audio (4.4a), each only
 * when the file or the episode has them. Episodes (not built yet) and Picture in Picture (not on desktop) fall through
 * to nothing.
 */
@Composable
private fun PillButton(
    item: PillItem,
    speedOpen: Boolean,
    sourcesOpen: Boolean,
    rate: Double,
    volume: Float,
    onSpeed: () -> Unit,
    onSources: () -> Unit,
    onVolume: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
    displayOpen: Boolean,
    onDisplay: () -> Unit,
    subtitlesOpen: Boolean,
    subtitlesOn: Boolean,
    onSubtitles: () -> Unit,
    audioOpen: Boolean,
    onAudio: () -> Unit,
) {
    when (item) {
        PillItem.Speed -> PlayerPillButton(
            symbol = item.symbol,
            selected = speedOpen || rate != 1.0,
            onClick = onSpeed,
            contentDescription = item.title,
        )
        PillItem.Sources -> PlayerPillButton(
            symbol = item.symbol,
            selected = sourcesOpen,
            onClick = onSources,
            contentDescription = item.title,
        )
        PillItem.Volume -> PlayerPillButton(
            symbol = if (volume < 0.001f) "speaker.slash" else item.symbol,
            onClick = onVolume,
            contentDescription = item.title,
        )
        PillItem.Next -> PlayerPillButton(
            symbol = item.symbol,
            onClick = onNext,
            enabled = nextEnabled,
            contentDescription = item.title,
        )
        PillItem.Display -> PlayerPillButton(
            symbol = item.symbol,
            selected = displayOpen,
            onClick = onDisplay,
            contentDescription = item.title,
        )
        PillItem.Subtitles -> PlayerPillButton(
            symbol = item.symbol,
            selected = subtitlesOpen || subtitlesOn,
            onClick = onSubtitles,
            contentDescription = item.title,
        )
        PillItem.Audio -> PlayerPillButton(
            symbol = item.symbol,
            selected = audioOpen,
            onClick = onAudio,
            contentDescription = item.title,
        )
        else -> Unit
    }
}

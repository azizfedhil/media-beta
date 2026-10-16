package com.mediahub.pear.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.meta.SkipSegment
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.floor

/**
 * The skip button and the next-episode button, bottom right above the bar (`skipLayer` in PlayerView.swift). One button
 * at a time, changing with the moment. The playhead is read in half-second steps, so the overlay isn't redrawn for every
 * frame of the video.
 *
 * @param controlsShown the controls are up: the button sits above the seek bar, and the manual skip is only offered then.
 * @param segments the timestamps of this episode; [loaded] is false until they have been looked up.
 * @param manualSeconds the manual skip's length when the setting is on, this is an episode and the controls are up; else null.
 * @param openedMidway the episode opened part way in (a resume), so the manual skip is not offered for it.
 */
@Composable
internal fun BoxScope.SkipButtons(
    engine: PlayerEngine,
    controlsShown: Boolean,
    segments: List<SkipSegment>,
    loaded: Boolean,
    hasNext: Boolean,
    manualSeconds: Double?,
    openedMidway: Boolean,
    onSeek: (Double) -> Unit,
    onNext: () -> Unit,
) {
    val position by remember(engine) {
        engine.positionSeconds.map { if (it.isFinite()) floor(it * 2) / 2 else 0.0 }.distinctUntilChanged()
    }.collectAsState(0.0)
    val duration by engine.durationSeconds.collectAsState()

    var used by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    val manualOffered = SkipRules.manualOffered(
        loaded = loaded,
        openedMidway = openedMidway,
        hasSegments = segments.isNotEmpty(),
        configured = manualSeconds != null,
        usedOrExpired = used || expired,
        position = position,
        duration = duration,
    )
    // Offered again (controls shown again), the countdown starts over; it is gone for good once it has run out.
    LaunchedEffect(manualOffered) {
        if (manualOffered) {
            delay(SkipRules.MANUAL_LIFE_MILLIS)
            expired = true
        }
    }

    val choice = SkipRules.choose(position, duration, segments, hasNext, if (manualOffered) manualSeconds else null)
    // Keeps the last button on screen while it fades out.
    var shown by remember { mutableStateOf<SkipChoice?>(null) }
    LaunchedEffect(choice) { if (choice != null) shown = choice }

    val bottom by animateDpAsState(
        targetValue = if (controlsShown) 140.dp else 40.dp,
        animationSpec = PearMotion.snappy<Dp>(PearMotion.BASE),
    )

    val margins = LocalPlayerMargins.current
    val metrics = LocalPlayerMetrics.current
    // The controls rise and move in by their extra margin on touch; the button moves by the same amount, so it keeps its place.
    val lift = (margins.bottom - metrics.bottomMarginDp).dp
    val inward = (margins.end - metrics.sideMarginDp).dp
    Box(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp + inward, bottom = bottom + lift)) {
        AnimatedVisibility(
            visible = choice != null,
            enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut(),
        ) {
            shown?.let { button ->
                SkipButton(
                    choice = button,
                    onClick = {
                        if (button.isFallback) used = true
                        val target = button.target
                        if (target != null) onSeek(target) else onNext()
                    },
                )
            }
        }
    }
}

/** The glass capsule with an icon and a label (`Label` in the skip layer of PlayerView.swift: 17 pt semibold, 48 pt tall). */
@Composable
private fun SkipButton(choice: SkipChoice, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, PearMotion.snappy<Float>(PearMotion.FAST))
    PlayerSurface(
        modifier = Modifier.height(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        kind = SurfaceKind.Capsule,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PearIcon(choice.symbol, size = 17.dp, tint = Color.White)
            PearText(
                choice.label,
                style = PearTheme.type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

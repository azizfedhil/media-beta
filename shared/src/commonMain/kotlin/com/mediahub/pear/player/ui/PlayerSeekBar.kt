package com.mediahub.pear.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The scrubber: elapsed time, a glass capsule track (buffered range lighter, played part white), time left.
 * Like `SeekBar` in PlayerView.swift, plus a time preview that follows the pointer (desktop hover).
 *
 * It reads the engine's clock itself, so only this composable redraws on position ticks. The clock is
 * rounded to 0.2 s first: mpv reports every frame, and nothing on this bar can show less than that.
 *
 * @param onScrubbing true while the bar is being dragged (the controls must not hide then).
 * @param openedAt resume point the source is opening at: shown until the engine reports its first position.
 */
@Composable
internal fun PlayerSeekBar(
    engine: PlayerEngine,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    openedAt: Double? = null,
) {
    val position by remember(engine) {
        engine.positionSeconds.map { floor(it * 5.0) / 5.0 }.distinctUntilChanged()
    }.collectAsState(0.0)
    val duration by engine.durationSeconds.collectAsState()
    val buffered by engine.bufferedSeconds.collectAsState()

    var scrub by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableStateOf<Float?>(null) }
    var trackWidth by remember { mutableIntStateOf(0) }

    // After a release the engine needs a moment to land on the new spot. Until it does (or 1.5 s pass) the bar
    // keeps showing the target instead of jumping back to the old position.
    var settleAt by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(settleAt) {
        if (settleAt != null) {
            delay(1500)
            settleAt = null
        }
    }
    LaunchedEffect(position) {
        val target = settleAt
        if (target != null && abs(position - target) < 2.0) settleAt = null
    }

    val canSeek = duration > 0.0
    val scrubTime = scrub?.let { SeekMath.timeAt(it, duration) }
    val shownTime = scrubTime ?: settleAt ?: openedAt?.takeIf { position <= 0.0 } ?: position
    val frac = SeekMath.fraction(shownTime, duration)
    val playedFrac = SeekMath.fraction(position, duration)
    val bufferedFrac = SeekMath.bufferedFraction(position, buffered, duration)

    val active = scrub != null
    val trackHeight by animateDpAsState(
        targetValue = when {
            active -> 20.dp
            hover != null -> 16.dp
            else -> 12.dp
        },
        animationSpec = PearMotion.snappy(PearMotion.FAST),
    )
    val knobScale by animateFloatAsState(if (active) 1f else 0.4f, PearMotion.snappy(PearMotion.FAST))
    val knobAlpha by animateFloatAsState(if (active) 1f else 0f, PearMotion.snappy(PearMotion.FAST))

    val timeStyle = PearTheme.type.subheadline.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val labelColor = Color.White.copy(alpha = 0.85f)

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PearText(Fmt.clock(shownTime), Modifier.width(58.dp), style = timeStyle, color = labelColor, maxLines = 1)

        Box(
            Modifier.weight(1f).height(40.dp)
                .onSizeChanged { trackWidth = it.width }
                .pointerHoverIcon(PointerIcon.Hand)
                .fractionPointer(
                    enabled = canSeek,
                    onHover = { hover = it },
                    onStart = {
                        scrub = it
                        onScrubbing(true)
                    },
                    onMove = { scrub = it },
                    onEnd = { f ->
                        val target = SeekMath.timeAt(f, duration)
                        scrub = null
                        onScrubbing(false)
                        settleAt = target
                        engine.seek(target)
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            PlayerSurface(Modifier.fillMaxWidth().height(trackHeight), SurfaceKind.Capsule) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    if (bufferedFrac > playedFrac) {
                        drawRect(Color.White.copy(alpha = 0.38f), Offset(w * playedFrac, 0f), Size(w * (bufferedFrac - playedFrac), h))
                    }
                    drawRect(Color.White, Offset.Zero, Size(w * frac, h))
                }
            }

            if (knobAlpha > 0.01f) {
                PlayerSurface(
                    Modifier.size(30.dp)
                        .offset { IntOffset((trackWidth * frac - 15.dp.toPx()).roundToInt(), 0) }
                        .graphicsLayer { scaleX = knobScale; scaleY = knobScale; alpha = knobAlpha },
                    kind = SurfaceKind.Circle,
                    tint = Color.White,
                ) {}
            }

            val previewFrac = scrub ?: hover
            if (previewFrac != null && canSeek) {
                PlayerSurface(
                    Modifier.align(Alignment.TopStart).layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val x = (trackWidth * previewFrac - p.width / 2f).roundToInt().coerceIn(0, maxOf(trackWidth - p.width, 0))
                        layout(p.width, p.height) { p.place(x, -p.height + 4.dp.roundToPx()) }
                    },
                    kind = SurfaceKind.Capsule,
                ) {
                    PearText(
                        Fmt.clock(SeekMath.timeAt(previewFrac, duration)),
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = timeStyle,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }

        PearText(
            SeekMath.remainingLabel(shownTime, duration),
            Modifier.width(62.dp),
            style = timeStyle,
            color = labelColor,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

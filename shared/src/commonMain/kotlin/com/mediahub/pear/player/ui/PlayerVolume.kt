package com.mediahub.pear.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearMotion

/**
 * Mute button with a slider that slides out while the pointer is over it (or while dragging it).
 * iOS shows a volume pill for 3 s instead; hover is the desktop version of the same idea.
 * Volume is the engine's own (0..1), so it is the player's volume, not the system mixer.
 *
 * @param onActive true while the slider is open: the controls must stay up.
 */
@Composable
internal fun PlayerVolumeControl(
    engine: PlayerEngine,
    onActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val volume by engine.volume.collectAsState()
    var lastAudible by remember { mutableStateOf(1f) }
    LaunchedEffect(volume) { if (volume > 0.01f) lastAudible = volume }

    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val open = hovered || dragging
    LaunchedEffect(open) { onActive(open) }
    DisposableEffect(Unit) { onDispose { onActive(false) } }

    val sliderWidth by animateDpAsState(if (open) 96.dp else 0.dp, PearMotion.snappy(PearMotion.BASE))

    Row(modifier.hoverable(source), verticalAlignment = Alignment.CenterVertically) {
        PlayerPillButton(
            symbol = volumeSymbol(volume),
            onClick = { engine.setVolume(toggledMute(engine.volume.value, lastAudible)) },
            contentDescription = if (volume > 0f) "Mute" else "Unmute",
        )
        if (sliderWidth > 0.dp) {
            VolumeSlider(
                value = volume,
                onChange = { engine.setVolume(it) },
                onDragging = { dragging = it },
                modifier = Modifier.width(sliderWidth).padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun VolumeSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.height(32.dp).fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .fractionPointer(
                onStart = {
                    onDragging(true)
                    onChange(it)
                },
                onMove = { onChange(it) },
                onEnd = {
                    onChange(it)
                    onDragging(false)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val trackH = 4.dp.toPx()
            val y = (size.height - trackH) / 2f
            val r = CornerRadius(trackH / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, trackH), r)
            drawRoundRect(Color.White, Offset(0f, y), Size(size.width * value.coerceIn(0f, 1f), trackH), r)
            val knob = 6.dp.toPx()
            val cx = (size.width * value.coerceIn(0f, 1f)).coerceIn(knob, size.width - knob)
            drawCircle(Color.White, knob, Offset(cx, size.height / 2f))
        }
    }
}

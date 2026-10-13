package com.mediahub.pear.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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

/**
 * The volume slider that replaces the pill's icons once the speaker is tapped (`VolumeSliderRow` in
 * PlayerVolume.swift): a mute button, then the slider. The volume is the engine's own (0..1), not the system mixer.
 * The pill goes back to its icons 3 s after the last change (see `OptionPill`).
 *
 * @param onEditing true when a drag starts, false when it ends; the pill keeps its countdown paused in between.
 */
@Composable
internal fun PlayerVolumeSliderRow(
    level: Float,
    onChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onEditing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlayerPillButton(
            symbol = volumeSymbol(level),
            onClick = onToggleMute,
            contentDescription = if (level < 0.001f) "Unmute" else "Mute",
        )
        PlayerSlider(
            fraction = level,
            onChange = onChange,
            onEditing = onEditing,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
    }
}

/**
 * A horizontal slider in the player's style: a white track that thickens while it is dragged, as the volume slider
 * on iOS does. [knob] draws a dot at the position (used by speed; volume has none, as on iOS). Press, drag and
 * release work for mouse and touch alike; [onEditing] reports the drag.
 */
@Composable
internal fun PlayerSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    onEditing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    knob: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier.height(40.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .fractionPointer(
                onStart = {
                    dragging = true
                    onEditing(true)
                    onChange(it)
                },
                onMove = { onChange(it) },
                onEnd = {
                    onChange(it)
                    dragging = false
                    onEditing(false)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val f = fraction.coerceIn(0f, 1f)
            val trackH = (if (dragging) 16.dp else 10.dp).toPx()
            val y = (size.height - trackH) / 2f
            val corner = CornerRadius(trackH / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y), Size(size.width, trackH), corner)
            drawRoundRect(Color.White, Offset(0f, y), Size(size.width * f, trackH), corner)
            if (knob) {
                val radius = 7.dp.toPx()
                val cx = (size.width * f).coerceIn(radius, size.width - radius)
                drawCircle(Color.White, radius, Offset(cx, size.height / 2f))
            }
        }
    }
}

package com.mediahub.pear.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier
import com.mediahub.pear.shell.PlatformCaps

/**
 * A placeholder fill with a soft highlight that sweeps across it. Clip the box first if it needs rounded corners.
 * The sweep is read while drawing, so it redraws and never recomposes.
 */
@Composable
fun Modifier.pearShimmer(
    base: Color = PearTheme.colors.fill,
    highlight: Color = PearTheme.colors.fillStrong,
): Modifier {
    // On Android, a Low phone or a page under the player draws only the static fill: the sweep would redraw every frame
    // for nothing. Desktop never takes this branch, so its sweep runs as before.
    if (PlatformCaps.isTouchFirst && (DeviceProfile.tier == DeviceTier.Low || !LocalPageActive.current)) {
        return this.drawWithContent {
            drawRect(base)
            drawContent()
        }
    }
    val transition = rememberInfiniteTransition(label = "pear-shimmer")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing), RepeatMode.Restart),
        label = "pear-shimmer-phase",
    )
    return this.drawWithContent {
        drawRect(base)
        val band = size.width * 0.6f
        val x = -band + (size.width + band * 2f) * phase.value
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, 0f),
            ),
        )
        drawContent()
    }
}

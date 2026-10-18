package com.mediahub.pear.glass

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the warm-up stays in the tree. The launch intro covers the app for about five seconds on desktop. */
private const val WARMUP_MILLIS = 4_000L

/**
 * Draws the two glass styles a player uses, once, while the launch intro covers the app. Their shaders are compiled then,
 * so the first glass frame of the player does not wait for them. It is removed after [WARMUP_MILLIS].
 */
@Composable
internal fun GlassWarmup(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(WARMUP_MILLIS)
        shown = false
    }
    if (shown) {
        Row(modifier.size(48.dp)) {
            GlassSurface(Modifier.size(24.dp), modeOverride = GlassMode.Glass) {}
            GlassSurface(Modifier.size(24.dp), modeOverride = GlassMode.Frosted) {}
        }
    }
}

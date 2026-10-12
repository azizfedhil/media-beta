package com.mediahub.pear.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where the engine's video is drawn, inside the Compose scene so glass can sit on top of it.
 * [performanceMode] caps the render size at 1080p and scales the picture up (for 4K).
 */
@Composable
expect fun VideoSurface(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    performanceMode: Boolean = false,
    onDoubleClick: () -> Unit = {},
)

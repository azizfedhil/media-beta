package com.mediahub.pear.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mediahub.pear.player.mpv.MpvPlayerEngine
import com.mediahub.pear.player.mpv.VideoFrame
import kotlinx.coroutines.flow.MutableStateFlow

private const val NORMAL_MAX_PIXELS = 3840L * 2160L
private const val PERFORMANCE_MAX_PIXELS = 1920L * 1080L

/** [sampleable] is ignored: desktop frames are drawn into a Compose canvas, so glass can always sample them. */
@Composable
actual fun VideoSurface(
    engine: PlayerEngine,
    modifier: Modifier,
    performanceMode: Boolean,
    onDoubleClick: () -> Unit,
    sampleable: Boolean,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val renderer = remember(engine) { (engine as? MpvPlayerEngine)?.createRenderer() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }

    LaunchedEffect(renderer, boxSize, performanceMode) {
        renderer?.setTargetSize(boxSize.width, boxSize.height, if (performanceMode) PERFORMANCE_MAX_PIXELS else NORMAL_MAX_PIXELS)
    }

    val frames = remember(renderer) { renderer?.frames ?: MutableStateFlow<VideoFrame?>(null) }
    val frameState = frames.collectAsState()

    Box(
        modifier.background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(onDoubleClick) { detectTapGestures(onDoubleTap = { onDoubleClick() }) },
    ) {
        // The frame is read inside the draw block, so a new frame redraws without recomposing anything.
        Canvas(Modifier.fillMaxSize()) {
            val frame = frameState.value ?: return@Canvas
            drawImage(
                image = frame.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

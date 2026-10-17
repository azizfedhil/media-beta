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
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.log.PearTrace
import com.mediahub.pear.log.TraceRecompose
import com.mediahub.pear.player.mpv.MpvPlayerEngine
import com.mediahub.pear.player.mpv.VideoFrame
import kotlinx.coroutines.flow.MutableStateFlow

private const val NORMAL_MAX_PIXELS = 3840L * 2160L
private const val PERFORMANCE_MAX_PIXELS = 1920L * 1080L

/** Trace only: plain fields the draw block updates. Not Compose state, so updating them never redraws anything. */
private class DrawTrace {
    var lastSeq = 0L
    var lastDrawNanos = 0L
    var lastScaledLogNanos = 0L
    var lastBoxLogNanos = 0L
}

/** [sampleable] is ignored: desktop frames are drawn into a Compose canvas, so glass can always sample them. */
@Composable
actual fun VideoSurface(
    engine: PlayerEngine,
    modifier: Modifier,
    performanceMode: Boolean,
    onDoubleClick: () -> Unit,
    sampleable: Boolean,
) {
    TraceRecompose("VideoSurface")
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val renderer = remember(engine) { (engine as? MpvPlayerEngine)?.createRenderer() }
    val trace = remember { DrawTrace() }
    DisposableEffect(renderer) {
        if (renderer != null) PearTrace.event("Player") { "surface: renderer attached" }
        onDispose {
            if (renderer != null) PearTrace.event("Player") { "surface: renderer disposed" }
            renderer?.close()
        }
    }

    LaunchedEffect(renderer, boxSize, performanceMode) {
        renderer?.setTargetSize(boxSize.width, boxSize.height, if (performanceMode) PERFORMANCE_MAX_PIXELS else NORMAL_MAX_PIXELS)
    }

    val frames = remember(renderer) { renderer?.frames ?: MutableStateFlow<VideoFrame?>(null) }
    val frameState = frames.collectAsState()

    Box(
        modifier.background(Color.Black)
            .onSizeChanged { next ->
                PearLog.context("video.box", "${next.width}x${next.height}")
                if (PearTrace.enabled) traceBoxChange(trace, boxSize, next)
                boxSize = next
            }
            .pointerInput(onDoubleClick) { detectTapGestures(onDoubleTap = { onDoubleClick() }) },
    ) {
        // The frame is read inside the draw block, so a new frame redraws without recomposing anything.
        Canvas(Modifier.fillMaxSize()) {
            val tr = PearTrace.enabled
            val blockStart = if (tr) System.nanoTime() else 0L
            if (tr) PearTrace.count("d.draw")
            val frame = frameState.value
            if (frame == null) {
                if (tr) {
                    PearTrace.count("d.nullFrame")
                    PearTrace.event("Draw") { "NO FRAME (null) boxSize=${size.width.toInt()}x${size.height.toInt()}" }
                }
                return@Canvas
            }
            // Hold the frame while it is drawn. The renderer may already have replaced it; then it is freed after this release.
            if (!frame.retain()) {
                if (tr) {
                    PearTrace.count("d.retainFail")
                    PearTrace.event("Draw") { "RETAIN FAILED seq=${frame.seq} (frame freed while drawing, nothing drawn)" }
                }
                return@Canvas
            }
            if (tr) traceDrawn(trace, frame, blockStart, size.width, size.height)
            try {
                drawImage(
                    image = frame.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low,
                )
            } finally {
                frame.release()
            }
            if (tr) PearTrace.sample("d.drawMs", (System.nanoTime() - blockStart) / 1_000_000.0)
        }
    }
}

/** Trace only, from the draw block: what was drawn, how old it was, whether it was skipped, and whether it was scaled. */
private fun traceDrawn(trace: DrawTrace, frame: VideoFrame, now: Long, canvasW: Float, canvasH: Float) {
    if (trace.lastDrawNanos != 0L) PearTrace.sample("d.sinceLastDrawMs", (now - trace.lastDrawNanos) / 1_000_000.0)
    trace.lastDrawNanos = now
    if (frame.seq == trace.lastSeq) {
        PearTrace.count("d.sameFrame")
    } else {
        PearTrace.count("d.newFrame")
        PearTrace.sample("d.frameAgeMs", (now - frame.publishedNanos) / 1_000_000.0)
        if (trace.lastSeq != 0L && frame.seq > trace.lastSeq + 1) {
            PearTrace.count("d.skippedFrames", frame.seq - trace.lastSeq - 1)
        }
        trace.lastSeq = frame.seq
        if (frame.seq % 30L == 0L) PearLog.context("video.frame", "seq=${frame.seq} ${frame.width}x${frame.height}")
    }
    val cw = canvasW.toInt()
    val ch = canvasH.toInt()
    if (frame.width != cw || frame.height != ch) {
        PearTrace.count("d.scaled")
        if (now - trace.lastScaledLogNanos >= 250_000_000L) {
            trace.lastScaledLogNanos = now
            PearTrace.event("Draw") { "scaled draw frame=${frame.width}x${frame.height} canvas=${cw}x$ch seq=${frame.seq}" }
        }
    }
}

/** Trace only: a change of the video's box. The count is always kept; the event is limited to one every 100 ms. */
private fun traceBoxChange(trace: DrawTrace, old: IntSize, next: IntSize) {
    PearTrace.count("ui.boxChanges")
    val now = System.nanoTime()
    if (now - trace.lastBoxLogNanos >= 100_000_000L) {
        trace.lastBoxLogNanos = now
        PearTrace.event("Layout") { "videoBox ${old.width}x${old.height} -> ${next.width}x${next.height}" }
    }
}

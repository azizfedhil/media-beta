package com.mediahub.pear.player

import androidx.compose.runtime.getValue
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.mediahub.pear.player.mpv.AndroidMpvEngine

/**
 * libmpv draws into a SurfaceView, the same kind of surface mpv-android uses. Blur (glass) cannot sample a SurfaceView, so
 * glass over video is flat on Android (decisions.md, D5).
 */
@Composable
actual fun VideoSurface(
    engine: PlayerEngine,
    modifier: Modifier,
    performanceMode: Boolean,
    onDoubleClick: () -> Unit,
) {
    val mpv = engine as? AndroidMpvEngine
    val onDouble by rememberUpdatedState(onDoubleClick)

    // One callback per engine: the surface's lifetime drives attach and detach.
    val callback = remember(mpv) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mpv?.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                mpv?.surfaceSizeChanged(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mpv?.detachSurface()
            }
        }
    }

    LaunchedEffect(mpv, performanceMode) { mpv?.setPerformanceMode(performanceMode) }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDouble() }) },
    ) {
        if (mpv != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).also { it.holder.addCallback(callback) }
                },
                onRelease = { view -> view.holder.removeCallback(callback) },
            )
        }
    }
}

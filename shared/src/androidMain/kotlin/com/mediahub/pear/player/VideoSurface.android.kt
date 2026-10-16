package com.mediahub.pear.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.mediahub.pear.player.mpv.AndroidMpvEngine

/**
 * libmpv draws the picture into a Surface, and one of two views can hold it:
 *
 * - a SurfaceView ([sampleable] false, the default): the cheapest path, the same as mpv-android. Glass cannot sample it, so
 *   the controls use the Frosted look over it.
 * - a TextureView ([sampleable] true, the Liquid Glass choice): the picture is a texture, so the glass blur can sample it.
 *   It costs more battery, so it is used only when the player is set to Liquid Glass (decisions.md, D5).
 *
 * The black background and the double-tap are the same on both paths.
 */
@Composable
actual fun VideoSurface(
    engine: PlayerEngine,
    modifier: Modifier,
    performanceMode: Boolean,
    onDoubleClick: () -> Unit,
    sampleable: Boolean,
) {
    val mpv = engine as? AndroidMpvEngine
    val onDouble by rememberUpdatedState(onDoubleClick)

    LaunchedEffect(mpv, performanceMode) { mpv?.setPerformanceMode(performanceMode) }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDouble() }) },
    ) {
        if (mpv != null) {
            // Keyed on the choice: a different view is built when it changes, and the old one is released first.
            key(sampleable) {
                if (sampleable) TextureVideo(mpv) else SurfaceVideo(mpv)
            }
        }
    }
}

/** The SurfaceView path, as it was before the Liquid Glass choice: mpv-android's lifecycle, unchanged. */
@Composable
private fun SurfaceVideo(mpv: AndroidMpvEngine) {
    // One callback per engine: the surface's lifetime drives attach and detach.
    val callback = remember(mpv) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mpv.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                mpv.surfaceSizeChanged(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mpv.detachSurface()
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).also { it.holder.addCallback(callback) }
        },
        onRelease = { view -> view.holder.removeCallback(callback) },
    )
}

/** The TextureView path, for the Liquid Glass choice (T3): the same engine calls as the SurfaceView path. */
@Composable
private fun TextureVideo(mpv: AndroidMpvEngine) {
    // One listener per engine: the texture's lifetime drives attach and detach.
    val listener = remember(mpv) {
        object : TextureView.SurfaceTextureListener {
            private var surface: Surface? = null

            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val s = Surface(st)
                surface = s
                mpv.attachSurface(s)
                mpv.surfaceSizeChanged(w, h)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                mpv.surfaceSizeChanged(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // Detach before the Surface is released: mpv must stop drawing into it first.
                mpv.detachSurface()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).also { it.surfaceTextureListener = listener }
        },
        onRelease = { view -> view.surfaceTextureListener = null },
    )
}

package com.mediahub.pear.player.mpv

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.mediahub.pear.log.PearLog
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One rendered frame. [image] may be wider than [width] (rows are padded to 16 px); draw only the top-left [width] x [height].
 *
 * Reference-counted: the renderer holds one reference until it publishes a newer frame, and every draw holds one for as
 * long as it is drawing. The native memory is freed only when the last reference is released, so a frame can never be
 * freed while the UI is still drawing it.
 */
class VideoFrame internal constructor(
    val image: ImageBitmap,
    private val source: Image,
    val width: Int,
    val height: Int,
) {
    private val lock = Any()
    private var refs = 1 // the renderer's reference
    private var freed = false

    /** Takes a reference for one draw. Returns false if the frame is already freed; the caller then draws nothing. */
    fun retain(): Boolean {
        synchronized(lock) {
            if (freed) return false
            refs++
            return true
        }
    }

    /** Drops a reference. The last one frees the native image. */
    fun release() {
        val free = synchronized(lock) {
            refs--
            if (refs == 0) {
                freed = true
                true
            } else {
                false
            }
        }
        if (free) source.close()
    }
}

/**
 * mpv software renderer: mpv draws each frame straight into Skia-owned pixel memory ("bgr0" into a BGRA bitmap), so
 * the only copy left is Skia making its immutable [Image]. Runs on its own thread; [frames] always holds the latest.
 */
class MpvVideoRenderer private constructor(
    private val mpv: LibMpv,
    private val ctx: Pointer,
) {
    private val renderLock = Any()
    @Volatile private var closed = false
    private val signal = Semaphore(0)

    private val _frames = MutableStateFlow<VideoFrame?>(null)
    val frames: StateFlow<VideoFrame?> = _frames.asStateFlow()

    @Volatile private var targetW = 0
    @Volatile private var targetH = 0
    @Volatile private var needRedraw = true

    private var bitmap: Bitmap? = null
    private var bitmapW = 0
    private var bitmapH = 0

    // Parameter blocks, allocated once. mpv_render_param is { int type; void *data }.
    private val ptrSize = Native.POINTER_SIZE
    private val paramStride = ptrSize * 2
    private val sizeMem = Memory(8)
    private val formatMem = Memory(8).also { it.setString(0, "bgr0") }
    private val strideMem = Memory(Native.SIZE_T_SIZE.toLong())
    private val params = Memory(paramStride * 5L)

    private val callback = object : MpvRenderUpdateCallback {
        override fun invoke(ctx: Pointer?) {
            signal.release()
        }
    }

    private val thread = Thread({
        while (!closed) {
            try {
                signal.acquire()
            } catch (_: InterruptedException) {
                break
            }
            signal.drainPermits()
            synchronized(renderLock) {
                if (closed) return@synchronized
                val flags = mpv.mpv_render_context_update(ctx)
                val newFrame = (flags and MpvRenderParam.UPDATE_FRAME) != 0L
                if (newFrame || needRedraw) {
                    needRedraw = false
                    renderOnce()
                }
            }
        }
    }, "mpv-render").apply { isDaemon = true }

    private fun setParam(index: Int, type: Int, data: Pointer?) {
        params.setInt(index.toLong() * paramStride, type)
        params.setPointer(index.toLong() * paramStride + ptrSize, data)
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap? {
        val paddedW = (w + 15) and 15.inv()
        if (bitmap != null && bitmapW == paddedW && bitmapH == h) return bitmap
        bitmap?.close()
        val bmp = Bitmap()
        val ok = bmp.allocPixels(ImageInfo(paddedW, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        if (!ok) {
            bitmap = null
            return null
        }
        bitmap = bmp
        bitmapW = paddedW
        bitmapH = h
        return bmp
    }

    private fun renderOnce() {
        val w = targetW
        val h = targetH
        if (w <= 0 || h <= 0) return
        val bmp = ensureBitmap(w, h) ?: return
        val addr = bmp.peekPixels()?.addr ?: return

        sizeMem.setInt(0, w)
        sizeMem.setInt(4, h)
        if (Native.SIZE_T_SIZE == 8) strideMem.setLong(0, bmp.rowBytes.toLong()) else strideMem.setInt(0, bmp.rowBytes)
        setParam(0, MpvRenderParam.SW_SIZE, sizeMem)
        setParam(1, MpvRenderParam.SW_FORMAT, formatMem)
        setParam(2, MpvRenderParam.SW_STRIDE, strideMem)
        setParam(3, MpvRenderParam.SW_POINTER, Pointer(addr))
        setParam(4, 0, null)

        if (mpv.mpv_render_context_render(ctx, params) < 0) return
        bmp.notifyPixelsChanged()

        // Skia copies the pixels (the shared bitmap is mutable), so the frame never refers to memory the next render writes.
        // On failure keep showing the last good frame; the next render tries again instead of killing this thread.
        val source = try {
            Image.makeFromBitmap(bmp)
        } catch (e: RuntimeException) {
            PearLog.w("Player", "Video frame skipped: could not copy the rendered picture", e)
            return
        }
        val frame = VideoFrame(source.toComposeImageBitmap(), source, w, h)
        val previous = _frames.value
        _frames.value = frame
        // Drops only the renderer's reference. A draw still using the previous frame keeps it alive until it finishes.
        previous?.release()
    }

    /** Render size in pixels. Above [maxPixels] it is scaled down, keeping the shape (4K performance mode). */
    fun setTargetSize(width: Int, height: Int, maxPixels: Long) {
        if (width <= 0 || height <= 0) return
        val px = width.toLong() * height
        val scale = if (px > maxPixels) sqrt(maxPixels.toDouble() / px) else 1.0
        targetW = max(2, (width * scale).toInt())
        targetH = max(2, (height * scale).toInt())
        needRedraw = true
        signal.release()
    }

    fun close() {
        if (closed) return
        closed = true
        signal.release()
        synchronized(renderLock) {
            mpv.mpv_render_context_set_update_callback(ctx, null, null)
            mpv.mpv_render_context_free(ctx)
            bitmap?.close()
            bitmap = null
            val last = _frames.value
            _frames.value = null
            last?.release()
        }
    }

    companion object {
        fun create(mpv: LibMpv, handle: Pointer): MpvVideoRenderer? {
            val stride = Native.POINTER_SIZE * 2
            val apiType = Memory(8).also { it.setString(0, "sw") }
            val p = Memory(stride * 2L)
            p.setInt(0, MpvRenderParam.API_TYPE)
            p.setPointer(Native.POINTER_SIZE.toLong(), apiType)
            p.setInt(stride.toLong(), 0)
            p.setPointer(stride.toLong() + Native.POINTER_SIZE, null)

            val out = PointerByReference()
            if (mpv.mpv_render_context_create(out, handle, p) < 0) return null
            val ctx = out.value ?: return null
            val renderer = MpvVideoRenderer(mpv, ctx)
            mpv.mpv_render_context_set_update_callback(ctx, renderer.callback, null)
            renderer.thread.start()
            renderer.signal.release() // draw the first frame
            return renderer
        }
    }
}

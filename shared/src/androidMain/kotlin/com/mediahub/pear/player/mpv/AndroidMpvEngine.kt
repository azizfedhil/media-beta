package com.mediahub.pear.player.mpv

import android.content.Context
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.Surface
import com.mediahub.pear.player.PipState
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.player.PlayerTrack
import com.mediahub.pear.player.SubtitleRendering
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier
import com.mediahub.pear.player.TrackKind
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * libmpv on Android, through the `dev.jdtech.mpv:libmpv` AAR (libmpv 0.41 with its own ffmpeg; the wrapper is MIT).
 *
 * Video goes straight to a Surface through mpv's GPU renderer, with MediaCodec doing the decoding, so decode and
 * drawing are both hardware. Everything else follows [com.mediahub.pear.player.mpv.MpvPlayerEngine] on desktop: the
 * same properties, track rules and subtitle split. Only the way the library is reached differs: the AAR's JNI wrapper
 * here, JNA on desktop.
 *
 * Threads: API calls run under [lock]. mpv's event thread calls back into this class WITHOUT the lock. Release waits
 * for that thread while holding the lock, so a callback that took the lock would deadlock.
 */
class AndroidMpvEngine private constructor(
    private val mpv: MPVLib,
) : PlayerEngine, MPVLib.EventObserver, MPVLib.LogObserver {

    private val lock = Any()
    /** Property changes arrive on mpv's thread; the picture's shape is read by the UI, so it is set on the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var released = false
    @Volatile private var surfaceAttached = false
    /** True from load() until mpv reports the new file starting; the old file's END_FILE in between is not an error. */
    @Volatile private var replacing = false
    /** The last error-level line mpv logged for the current file. END_FILE carries no error code, so this explains it. */
    @Volatile private var lastError: String? = null
    @Volatile private var appDrawsSubtitles = false
    @Volatile private var performanceMode = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    private val _state = MutableStateFlow(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionSeconds = MutableStateFlow(0.0)
    override val positionSeconds: StateFlow<Double> = _positionSeconds.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0.0)
    override val durationSeconds: StateFlow<Double> = _durationSeconds.asStateFlow()

    private val _bufferedSeconds = MutableStateFlow(0.0)
    override val bufferedSeconds: StateFlow<Double> = _bufferedSeconds.asStateFlow()

    private val _playbackRate = MutableStateFlow(1.0)
    override val playbackRate: StateFlow<Double> = _playbackRate.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val audioTracks: StateFlow<List<PlayerTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val subtitleTracks: StateFlow<List<PlayerTrack>> = _subtitleTracks.asStateFlow()

    private val _subtitleText = MutableStateFlow<String?>(null)
    override val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    // ---- calls from the API side (under the lock) -------------------------------------------------------------

    /** Runs a call on mpv under the lock, and never after the handle has been destroyed. */
    private inline fun call(block: (MPVLib) -> Unit) {
        synchronized(lock) {
            if (destroyed) return
            try {
                block(mpv)
            } catch (_: IllegalStateException) {
                // The wrapper refuses calls once its handle is gone. Nothing is left to do.
            }
        }
    }

    // ---- calls from mpv's event thread (no lock; see the class comment) ------------------------------------

    private fun rawGet(name: String): String? =
        if (destroyed) null else try { mpv.getPropertyString(name) } catch (_: IllegalStateException) { null }

    private fun rawSet(name: String, value: String) {
        if (destroyed) return
        try { mpv.setPropertyString(name, value) } catch (_: IllegalStateException) { /* handle going away */ }
    }

    private fun refreshTrackList() {
        val count = rawGet("track-list/count")?.toIntOrNull() ?: 0
        val audio = mutableListOf<PlayerTrack>()
        val subs = mutableListOf<PlayerTrack>()
        for (i in 0 until count) {
            val type = rawGet("track-list/$i/type") ?: continue
            val id = rawGet("track-list/$i/id")?.toIntOrNull() ?: continue
            val kind = when (type) {
                "audio" -> TrackKind.Audio
                "sub" -> TrackKind.Subtitle
                else -> continue // video tracks are not listed, as on desktop
            }
            val track = PlayerTrack(
                id = id,
                kind = kind,
                title = rawGet("track-list/$i/title") ?: "Track $id",
                language = rawGet("track-list/$i/lang"),
                isSelected = rawGet("track-list/$i/selected") == "yes",
                isDefault = rawGet("track-list/$i/default") == "yes",
                isForced = rawGet("track-list/$i/forced") == "yes",
                codec = rawGet("track-list/$i/codec"),
            )
            if (kind == TrackKind.Audio) audio.add(track) else subs.add(track)
        }
        _audioTracks.value = audio
        _subtitleTracks.value = subs
        applySubtitleRendering(subs.firstOrNull { it.isSelected })
    }

    /**
     * Plain-text subtitles are drawn by the app from [subtitleText], so mpv stops drawing them (`sub-visibility`). Every
     * other track (ASS and bitmap included) stays in the picture, as on desktop.
     */
    private fun applySubtitleRendering(selected: PlayerTrack?) {
        val draws = SubtitleRendering.drawnByApp(selected)
        appDrawsSubtitles = draws
        if (!draws) _subtitleText.value = null
        rawSet("sub-visibility", if (draws) "no" else "yes")
    }

    private fun observeProperties() {
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        mpv.observeProperty("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    }

    // ---- mpv events ------------------------------------------------------------------------------------------

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                replacing = false
                lastError = null
                // The lists belong to the file being left: clear them so the language pick never uses old tracks.
                _audioTracks.value = emptyList()
                _subtitleTracks.value = emptyList()
                _subtitleText.value = null
                _state.value = PlaybackState.Loading
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                refreshTrackList()
                // For a device check: which decoder mpv picked (mediacodec = hardware; no = software). See logcat "PearPlayer".
                Log.i(TAG, "loaded, decoder=${decoderInUse()}")
                _state.value = PlaybackState.Playing
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (replacing) return // a new load is replacing this file: not an end the viewer sees
                if (rawGet("eof-reached") == "yes") {
                    _state.value = PlaybackState.Ended
                } else {
                    Log.w(TAG, "failed: ${lastError ?: "no error line from mpv"}")
                    _errorMessage.value = MpvErrorText.describe(lastError)
                    _state.value = PlaybackState.Error
                }
            }
            else -> Unit
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> if (_state.value != PlaybackState.Loading && _state.value != PlaybackState.Ended) {
                _state.value = if (value) PlaybackState.Paused else PlaybackState.Playing
            }
            "paused-for-cache" -> {
                if (value && _state.value == PlaybackState.Playing) {
                    _state.value = PlaybackState.Buffering
                } else if (!value && _state.value == PlaybackState.Buffering) {
                    _state.value = PlaybackState.Playing
                }
            }
            // keep-open=always: the end of the file arrives here as well as in END_FILE.
            "eof-reached" -> if (value && _state.value != PlaybackState.Error) _state.value = PlaybackState.Ended
        }
    }

    override fun eventProperty(property: String, value: Long) {
        if (property == "track-list/count") refreshTrackList()
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                // A seek back is always written. Smaller steps are skipped, because mpv reports the position on every frame.
                val last = _positionSeconds.value
                if (value < last || abs(value - last) >= POSITION_STEP_SECONDS) _positionSeconds.value = value
            }
            "duration" -> _durationSeconds.value = value
            "demuxer-cache-time" -> if (abs(value - _bufferedSeconds.value) >= BUFFER_STEP_SECONDS) _bufferedSeconds.value = value
            "speed" -> _playbackRate.value = value
            "volume" -> _volume.value = (value / 100.0).toFloat().coerceIn(0f, 1f)
            "video-params/aspect" -> if (value > 0.0) mainHandler.post { PipState.videoAspect = value }
        }
    }

    override fun eventProperty(property: String, value: String) {
        // Only while the app draws the track: mpv's own text is not what the viewer sees then.
        if (property == "sub-text" && appDrawsSubtitles) {
            _subtitleText.value = value.takeIf { it.isNotBlank() }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // mpv levels: a smaller number is more serious. Fatal (10) and error (20) lines explain a file that failed.
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR && text.isNotBlank()) {
            lastError = "[$prefix] ${text.trim()}"
        }
    }

    // ---- PlayerEngine ----------------------------------------------------------------------------------------

    override suspend fun load(
        url: String,
        headers: Map<String, String>,
        startPositionSeconds: Double,
        hardwareDecoding: Boolean,
    ) {
        if (released) return
        _state.value = PlaybackState.Loading
        _errorMessage.value = null
        lastError = null
        replacing = true
        call { m ->
            // change-list: clearing it stops the last source's headers from leaking into this one (as on desktop).
            m.command(arrayOf("change-list", "http-header-fields", "clr", ""))
            headers.forEach { (k, v) -> m.command(arrayOf("change-list", "http-header-fields", "append", "$k: $v")) }
            m.setPropertyString("hwdec", if (hardwareDecoding) HARDWARE_DECODERS else "no")
            // Always set, so a resume point never sticks to the next file.
            m.setPropertyString("start", if (startPositionSeconds > 0) startPositionSeconds.toString() else "none")
            m.setPropertyString("pause", "no")
            m.command(arrayOf("loadfile", url, "replace"))
        }
    }

    override fun play() = call { it.setPropertyBoolean("pause", false) }

    override fun pause() = call { it.setPropertyBoolean("pause", true) }

    override fun togglePlay() = call { it.command(arrayOf("cycle", "pause")) }

    override fun seek(positionSeconds: Double) =
        call { it.command(arrayOf("seek", positionSeconds.toString(), "absolute+exact")) }

    override fun setRate(rate: Double) = call { it.setPropertyDouble("speed", rate) }

    override fun setVolume(volume: Float) {
        val percent = (volume.coerceIn(0f, 1f) * 100).toDouble()
        call { it.setPropertyDouble("volume", percent) }
    }

    override fun selectTrack(kind: TrackKind, trackId: Int?) {
        val prop = when (kind) {
            TrackKind.Audio -> "aid"
            TrackKind.Subtitle -> "sid"
            TrackKind.Video -> "vid"
        }
        val value = trackId?.toString() ?: "no"
        call { it.setPropertyString(prop, value) }
        if (kind == TrackKind.Subtitle) {
            // mpv applies the choice on its own thread and reports the list later: update here so the drawing follows
            // at once, and the next track-list event confirms it.
            val updated = _subtitleTracks.value.map { it.copy(isSelected = it.id == trackId) }
            _subtitleTracks.value = updated
            applySubtitleRendering(updated.firstOrNull { it.isSelected })
        } else {
            call { refreshTrackList() }
        }
    }

    override fun setAspectMode(mode: AspectMode) = call {
        it.setPropertyString("keepaspect", if (mode == AspectMode.Stretch) "no" else "yes")
        it.setPropertyString("panscan", if (mode == AspectMode.Fill) "1.0" else "0.0")
    }

    /**
     * Not wired yet. The GPU renderer tone-maps HDR to SDR by default; a switch to turn that off still needs the right mpv
     * option checked on a device (see docs/android/decisions.md, known gaps). The player hides this control later (E1).
     */
    override fun setHdrToneMapping(enabled: Boolean) = Unit

    override fun setAudioDelay(seconds: Double) = call { it.setPropertyString("audio-delay", seconds.toString()) }

    override fun setSubtitleDelay(seconds: Double) = call { it.setPropertyString("sub-delay", seconds.toString()) }

    override suspend fun addExternalSubtitle(url: String, title: String, language: String?) {
        call { m ->
            val args = if (language != null) {
                arrayOf("sub-add", url, "select", title, language)
            } else {
                arrayOf("sub-add", url, "select", title)
            }
            m.command(args)
        }
    }

    override fun release() {
        if (released) return
        released = true
        // mpv's destroy waits for its event thread, which may be inside one of our callbacks. The wait runs on its own
        // thread, so the UI thread that releases the engine never blocks on it.
        Thread({ destroyNow() }, "mpv-destroy").start()
    }

    private fun destroyNow() {
        // The lock is held through the join. Callbacks never take it (see the class comment), so this cannot deadlock.
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            if (surfaceAttached) {
                runCatching { mpv.setPropertyString("vo", "null") }
                runCatching { mpv.detachSurface() }
                surfaceAttached = false
            }
            runCatching { mpv.destroy() }
        }
    }

    // ---- video surface (called by VideoSurface.android.kt) --------------------------------------------------

    /** The SurfaceView's Surface is up: hand it to mpv as the window. Mirrors mpv-android's surfaceCreated. */
    fun attachSurface(surface: Surface) = call { m ->
        m.attachSurface(surface)
        surfaceAttached = true
        m.setOptionString("force-window", "yes")
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            val (w, h) = cappedSize(surfaceWidth, surfaceHeight)
            m.setPropertyString("android-surface-size", "${w}x$h")
        }
    }

    /** The surface changed size. Performance mode scales the picture down inside mpv; the surface keeps its size. */
    fun surfaceSizeChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (width <= 0 || height <= 0) return
        val (w, h) = cappedSize(width, height)
        call { it.setPropertyString("android-surface-size", "${w}x$h") }
    }

    /** The surface is going away. Mirrors mpv-android's surfaceDestroyed: stop the video output before detaching. */
    fun detachSurface() = call { m ->
        if (!surfaceAttached) return@call
        m.setPropertyString("vo", "null")
        m.setOptionString("force-window", "no")
        m.detachSurface()
        surfaceAttached = false
    }

    /** Performance mode caps the rendered size at 1080p. Applied at once when a surface is already sized. */
    fun setPerformanceMode(enabled: Boolean) {
        if (performanceMode == enabled) return
        performanceMode = enabled
        if (surfaceWidth > 0 && surfaceHeight > 0) surfaceSizeChanged(surfaceWidth, surfaceHeight)
    }

    private fun cappedSize(width: Int, height: Int): Pair<Int, Int> {
        if (!performanceMode) return width to height
        val pixels = width.toLong() * height
        if (pixels <= PERFORMANCE_MAX_PIXELS) return width to height
        val scale = sqrt(PERFORMANCE_MAX_PIXELS.toDouble() / pixels)
        return max(2, (width * scale).toInt()) to max(2, (height * scale).toInt())
    }

    /**
     * The decoder mpv is using now: "mediacodec" (zero-copy, hardware), "mediacodec-copy" (hardware, frames copied back to
     * RAM) or "no" (software). Use it to confirm hardware decoding on a device.
     */
    fun decoderInUse(): String = rawGet("hwdec-current") ?: "no"

    companion object {
        /** Hardware first: zero-copy when the video output allows it, copy-back otherwise. Software is the fallback mpv takes. */
        private const val HARDWARE_DECODERS = "mediacodec,mediacodec-copy"
        private const val PERFORMANCE_MAX_PIXELS = 1920L * 1080L
        /** The position is written only after it moves this far, or when it goes back. A guess. */
        private const val POSITION_STEP_SECONDS = 0.1
        /** The buffered amount is written only after it moves this far. A guess. */
        private const val BUFFER_STEP_SECONDS = 0.5
        private const val TAG = "PearPlayer"

        /**
         * Starts libmpv for this process. Throws [IllegalStateException] with a reason when it cannot start.
         * [caCertFile] is the CA bundle for https sources. Without it, https sources are not verified (see D15).
         */
        fun create(context: Context, caCertFile: File?): AndroidMpvEngine {
            val mpv = MPVLib.create(context.applicationContext)
                ?: throw IllegalStateException("libmpv could not allocate a player.")
            val engine = AndroidMpvEngine(mpv)

            // Options that must be set before libmpv starts. The GPU output on the Android window is what makes
            // hwdec=mediacodec zero-copy possible; mpv-android uses the same set.
            mpv.setOptionString("config", "no")
            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("force-window", "no")
            mpv.setOptionString("idle", "yes")
            mpv.setOptionString("keep-open", "always")
            mpv.setOptionString("volume-max", "100")
            mpv.setOptionString("ytdl", "no")
            mpv.setOptionString("ao", "audiotrack,opensles")
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            if (caCertFile != null) {
                mpv.setOptionString("tls-ca-file", caCertFile.absolutePath)
                mpv.setOptionString("tls-verify", "yes")
            }

            // Cache and picture options by tier: a Low phone reads less ahead and scales more cheaply. Each is wrapped, so a
            // libmpv build that does not know one ignores it and the player still starts.
            val tier = DeviceProfile.tier
            val (readAhead, backBuffer) = when (tier) {
                DeviceTier.Low -> "24MiB" to "8MiB"
                DeviceTier.Mid -> "48MiB" to "16MiB"
                DeviceTier.High -> "96MiB" to "32MiB"
            }
            val options = mutableListOf(
                "cache" to "yes",
                "demuxer-max-bytes" to readAhead,
                "demuxer-max-back-bytes" to backBuffer,
                "scale" to "bilinear",
                "dscale" to "bilinear",
                "cscale" to "bilinear",
                "correct-downscaling" to "no",
                "linear-downscaling" to "no",
                "sigmoid-upscaling" to "no",
                "dither-depth" to "no",
                "deband" to "no",
                "interpolation" to "no",
                "hdr-compute-peak" to "no",
            )
            if (tier == DeviceTier.Low) options += listOf("vd-lavc-fast" to "yes", "vd-lavc-skiploopfilter" to "nonref")
            for ((name, value) in options) runCatching { mpv.setOptionString(name, value) }

            // Observers before init, so the first START_FILE and property events are not missed.
            mpv.addObserver(engine)
            mpv.addLogObserver(engine)
            try {
                mpv.init()
            } catch (e: RuntimeException) {
                // The wrapper reports a failed init as a RuntimeException. Free the handle before reporting it.
                mpv.destroy()
                throw IllegalStateException("libmpv failed to start: ${e.message}", e)
            }
            engine.observeProperties()
            return engine
        }
    }
}

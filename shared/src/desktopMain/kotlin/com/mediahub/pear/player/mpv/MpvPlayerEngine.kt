package com.mediahub.pear.player.mpv

import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.DummyPlayerEngine
import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.player.PlayerTrack
import com.mediahub.pear.player.TrackKind
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MpvPlayerEngine private constructor(
    private val mpv: LibMpv,
    private val handle: Pointer,
) : PlayerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isReleased = AtomicBoolean(false)
    private val lock = Any()
    private var destroyed = false

    @Volatile private var renderer: MpvVideoRenderer? = null
    private var hdrToneMap = true
    private var hdrFilterOn = false

    /** Runs a native call only while the handle is alive; destroy takes the same lock, so no use-after-free. */
    private inline fun <T> native(default: T, block: () -> T): T = synchronized(lock) { if (destroyed) default else block() }

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

    init {
        // Observe properties
        mpv.mpv_observe_property(handle, 1L, "pause", MpvFormat.FLAG)
        mpv.mpv_observe_property(handle, 2L, "time-pos", MpvFormat.DOUBLE)
        mpv.mpv_observe_property(handle, 3L, "duration", MpvFormat.DOUBLE)
        mpv.mpv_observe_property(handle, 4L, "demuxer-cache-time", MpvFormat.DOUBLE)
        mpv.mpv_observe_property(handle, 5L, "paused-for-cache", MpvFormat.FLAG)
        mpv.mpv_observe_property(handle, 6L, "speed", MpvFormat.DOUBLE)
        mpv.mpv_observe_property(handle, 7L, "volume", MpvFormat.DOUBLE)
        mpv.mpv_observe_property(handle, 8L, "track-list/count", MpvFormat.INT64)
        mpv.mpv_observe_property(handle, 9L, "eof-reached", MpvFormat.FLAG)
        mpv.mpv_observe_property(handle, 10L, "video-params/gamma", MpvFormat.STRING)

        // Event loop thread
        val eventThread = Thread({
            while (!isReleased.get()) {
                val eventPtr = mpv.mpv_wait_event(handle, 0.1) ?: continue
                val event = MpvEvent(eventPtr)
                when (event.event_id) {
                    MpvEventId.SHUTDOWN -> break
                    MpvEventId.START_FILE -> {
                        _state.value = PlaybackState.Loading
                    }
                    MpvEventId.FILE_LOADED -> {
                        refreshTrackList()
                        _state.value = PlaybackState.Playing
                    }
                    MpvEventId.END_FILE -> {
                        // struct mpv_event_end_file { int reason; int error; ... }
                        val d = event.data
                        val reason = d?.getInt(0) ?: 0
                        val err = d?.getInt(4) ?: 0
                        when (reason) {
                            END_REASON_ERROR -> {
                                _errorMessage.value = native<String?>(null) { mpv.mpv_error_string(err) } ?: "Could not play this source."
                                _state.value = PlaybackState.Error
                            }
                            END_REASON_EOF -> _state.value = PlaybackState.Ended
                            else -> {} // stop/redirect: a new file is replacing this one
                        }
                    }
                    MpvEventId.PROPERTY_CHANGE -> {
                        if (event.data != null) {
                            val prop = MpvEventProperty(event.data!!)
                            handlePropertyChange(prop)
                        }
                    }
                }
            }
            // The render context must go before the mpv handle.
            renderer?.close()
            synchronized(lock) {
                destroyed = true
                mpv.mpv_terminate_destroy(handle)
            }
        }, "mpv-event-loop")
        eventThread.isDaemon = true
        eventThread.start()
    }

    private fun handlePropertyChange(prop: MpvEventProperty) {
        val name = prop.name ?: return
        val data = prop.data ?: return

        when (name) {
            "time-pos" -> {
                if (prop.format == MpvFormat.DOUBLE) {
                    _positionSeconds.value = data.getDouble(0)
                }
            }
            "duration" -> {
                if (prop.format == MpvFormat.DOUBLE) {
                    _durationSeconds.value = data.getDouble(0)
                }
            }
            "demuxer-cache-time" -> {
                if (prop.format == MpvFormat.DOUBLE) {
                    _bufferedSeconds.value = data.getDouble(0)
                }
            }
            "pause" -> {
                if (prop.format == MpvFormat.FLAG) {
                    val isPaused = data.getInt(0) != 0
                    if (_state.value != PlaybackState.Loading && _state.value != PlaybackState.Ended) {
                        _state.value = if (isPaused) PlaybackState.Paused else PlaybackState.Playing
                    }
                }
            }
            "paused-for-cache" -> {
                if (prop.format == MpvFormat.FLAG) {
                    val buffering = data.getInt(0) != 0
                    if (buffering && _state.value == PlaybackState.Playing) {
                        _state.value = PlaybackState.Buffering
                    } else if (!buffering && _state.value == PlaybackState.Buffering) {
                        _state.value = PlaybackState.Playing
                    }
                }
            }
            "video-params/gamma" -> {
                if (prop.format == MpvFormat.STRING) {
                    val gamma = data.getPointer(0)?.getString(0)
                    val hdr = gamma == "pq" || gamma == "hlg"
                    native(Unit) { applyHdrFilter(hdr && hdrToneMap) }
                }
            }
            "eof-reached" -> {
                // keep-open=always: end of file is reported here, not via END_FILE
                if (prop.format == MpvFormat.FLAG && data.getInt(0) != 0 && _state.value != PlaybackState.Error) {
                    _state.value = PlaybackState.Ended
                }
            }
            "speed" -> {
                if (prop.format == MpvFormat.DOUBLE) {
                    _playbackRate.value = data.getDouble(0)
                }
            }
            "volume" -> {
                if (prop.format == MpvFormat.DOUBLE) {
                    _volume.value = (data.getDouble(0) / 100.0).toFloat().coerceIn(0f, 1f)
                }
            }
            "track-list/count" -> {
                refreshTrackList()
            }
        }
    }

    private fun refreshTrackList() {
        val countStr = getPropertyString("track-list/count")?.toIntOrNull() ?: 0
        val aTracks = mutableListOf<PlayerTrack>()
        val sTracks = mutableListOf<PlayerTrack>()

        for (i in 0 until countStr) {
            val type = getPropertyString("track-list/$i/type") ?: continue
            val id = getPropertyString("track-list/$i/id")?.toIntOrNull() ?: continue
            val title = getPropertyString("track-list/$i/title") ?: "Track $id"
            val lang = getPropertyString("track-list/$i/lang")
            val isSelected = getPropertyString("track-list/$i/selected") == "yes"
            val isDefault = getPropertyString("track-list/$i/default") == "yes"
            val isForced = getPropertyString("track-list/$i/forced") == "yes"
            val codec = getPropertyString("track-list/$i/codec")

            val kind = when (type) {
                "audio" -> TrackKind.Audio
                "sub" -> TrackKind.Subtitle
                else -> TrackKind.Video
            }
            val track = PlayerTrack(id, kind, title, lang, isSelected, isDefault, isForced, codec)

            if (kind == TrackKind.Audio) aTracks.add(track)
            if (kind == TrackKind.Subtitle) sTracks.add(track)
        }

        _audioTracks.value = aTracks
        _subtitleTracks.value = sTracks
    }

    private fun getPropertyString(name: String): String? {
        return native<String?>(null) {
            val ptr = mpv.mpv_get_property_string(handle, name)
            if (ptr == null) null else try {
                ptr.getString(0)
            } finally {
                mpv.mpv_free(ptr)
            }
        }
    }

    override suspend fun load(
        url: String,
        headers: Map<String, String>,
        startPositionSeconds: Double,
        hardwareDecoding: Boolean,
    ) {
        if (isReleased.get()) return
        _state.value = PlaybackState.Loading
        _errorMessage.value = null

        val err = native(0) {
            // Headers via change-list: no comma escaping, and clearing stops the last source's headers leaking.
            mpv.mpv_command(handle, arrayOf("change-list", "http-header-fields", "clr", "", null))
            headers.forEach { (k, v) ->
                mpv.mpv_command(handle, arrayOf("change-list", "http-header-fields", "append", "$k: $v", null))
            }
            mpv.mpv_set_property_string(handle, "hwdec", if (hardwareDecoding) "auto-copy-safe" else "no")
            // Always set, so a previous resume point never sticks to the next file.
            mpv.mpv_set_property_string(handle, "start", if (startPositionSeconds > 0) startPositionSeconds.toString() else "none")
            mpv.mpv_set_property_string(handle, "pause", "no")
            mpv.mpv_command(handle, arrayOf("loadfile", url, "replace", null))
        }
        if (err < 0) {
            val msg = native<String?>(null) { mpv.mpv_error_string(err) } ?: "Could not load media."
            _errorMessage.value = msg
            _state.value = PlaybackState.Error
        }
    }

    override fun play() {
        native(0) { mpv.mpv_set_property_string(handle, "pause", "no") }
    }

    override fun pause() {
        native(0) { mpv.mpv_set_property_string(handle, "pause", "yes") }
    }

    override fun togglePlay() {
        native(0) { mpv.mpv_command(handle, arrayOf("cycle", "pause", null)) }
    }

    override fun seek(positionSeconds: Double) {
        native(0) { mpv.mpv_command(handle, arrayOf("seek", positionSeconds.toString(), "absolute+exact", null)) }
    }

    override fun setRate(rate: Double) {
        native(0) { mpv.mpv_set_property_string(handle, "speed", rate.toString()) }
    }

    override fun setVolume(volume: Float) {
        val clamped = (volume.coerceIn(0f, 1f) * 100).toInt()
        native(0) { mpv.mpv_set_property_string(handle, "volume", clamped.toString()) }
    }

    override fun selectTrack(kind: TrackKind, trackId: Int?) {
        val prop = when (kind) {
            TrackKind.Audio -> "aid"
            TrackKind.Subtitle -> "sid"
            TrackKind.Video -> "vid"
        }
        val value = trackId?.toString() ?: "no"
        native(0) { mpv.mpv_set_property_string(handle, prop, value) }
        refreshTrackList()
    }

    /** Frames only reach the screen through a render context; [VideoSurface] asks for one. */
    fun createRenderer(): MpvVideoRenderer? = synchronized(lock) {
        if (destroyed) return@synchronized null
        renderer?.close()
        MpvVideoRenderer.create(mpv, handle).also { renderer = it }
    }

    override fun setAspectMode(mode: AspectMode) {
        native(0) {
            mpv.mpv_set_property_string(handle, "keepaspect", if (mode == AspectMode.Stretch) "no" else "yes")
            mpv.mpv_set_property_string(handle, "panscan", if (mode == AspectMode.Fill) "1.0" else "0.0")
            0
        }
    }

    override fun setHdrToneMapping(enabled: Boolean) {
        hdrToneMap = enabled
        if (!enabled) native(Unit) { applyHdrFilter(false) }
    }

    /** Software rendering has no GPU tone mapper, so HDR goes through ffmpeg's zscale + tonemap before the copy. Call under [lock]. */
    private fun applyHdrFilter(on: Boolean) {
        if (on == hdrFilterOn) return
        hdrFilterOn = on
        if (on) {
            mpv.mpv_command(handle, arrayOf("vf", "add", "@pearhdr:lavfi=[zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p]", null))
        } else {
            mpv.mpv_command(handle, arrayOf("vf", "remove", "@pearhdr", null))
        }
    }

    override fun release() {
        // The event thread sees the flag within 100 ms and destroys the handle itself, never from under itself.
        isReleased.set(true)
    }

    companion object {
        private const val END_REASON_EOF = 0
        private const val END_REASON_ERROR = 4

        fun create(): PlayerEngine {
            val found = MpvNativeLoader.loadWithReport()
            val mpv = found.lib ?: return DummyPlayerEngine(found.describeFailure())

            val handle = mpv.mpv_create()
                ?: return DummyPlayerEngine("Could not allocate mpv client handle.")

            // Base options before initialization
            mpv.mpv_set_option_string(handle, "config", "no")
            // Frames are pulled through the render API (see MpvVideoRenderer), never shown in an mpv window.
            mpv.mpv_set_option_string(handle, "vo", "libmpv")
            mpv.mpv_set_option_string(handle, "idle", "yes")
            mpv.mpv_set_option_string(handle, "keep-open", "always")
            mpv.mpv_set_option_string(handle, "volume-max", "100")
            mpv.mpv_set_option_string(handle, "ytdl", "no")
            mpv.mpv_set_option_string(handle, "input-default-bindings", "no")
            mpv.mpv_set_option_string(handle, "input-vo-keyboard", "no")

            val initErr = mpv.mpv_initialize(handle)
            if (initErr < 0) {
                mpv.mpv_destroy(handle)
                val msg = mpv.mpv_error_string(initErr) ?: "Failed to initialize mpv."
                return DummyPlayerEngine(msg)
            }

            return MpvPlayerEngine(mpv, handle)
        }
    }
}
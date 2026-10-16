package com.mediahub.pear.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fallback player engine when native playback libraries are not present in the runtime. */
class DummyPlayerEngine(
    initialError: String? = null,
) : PlayerEngine {
    private val _state = MutableStateFlow(if (initialError != null) PlaybackState.Error else PlaybackState.Idle)
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

    private val _errorMessage = MutableStateFlow(initialError)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val audioTracks: StateFlow<List<PlayerTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val subtitleTracks: StateFlow<List<PlayerTrack>> = _subtitleTracks.asStateFlow()

    private val _subtitleText = MutableStateFlow<String?>(null)
    override val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    override suspend fun load(
        url: String,
        headers: Map<String, String>,
        startPositionSeconds: Double,
        hardwareDecoding: Boolean,
    ) {
        if (_errorMessage.value == null) {
            _errorMessage.value = "Playback engine is unavailable."
            _state.value = PlaybackState.Error
        }
    }

    override fun play() {}
    override fun pause() {}
    override fun togglePlay() {}
    override fun seek(positionSeconds: Double) {}
    override fun setRate(rate: Double) { _playbackRate.value = rate }
    override fun setVolume(volume: Float) { _volume.value = volume }
    override fun selectTrack(kind: TrackKind, trackId: Int?) {}
    override fun release() {}
}
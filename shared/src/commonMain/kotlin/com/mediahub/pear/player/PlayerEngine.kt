package com.mediahub.pear.player

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState {
    Idle,
    Loading,
    Buffering,
    Playing,
    Paused,
    Ended,
    Error,
}

/** How video fills the surface: Fit keeps the aspect (black bars), Fill crops to cover, Stretch ignores the aspect. */
enum class AspectMode { Fit, Fill, Stretch }

enum class TrackKind {
    Audio,
    Subtitle,
    Video,
}

data class PlayerTrack(
    val id: Int,
    val kind: TrackKind,
    val title: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val codec: String? = null,
)

/**
 * Universal media player engine interface. Implemented by libmpv on desktop and Media3 on Android.
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackState>
    val positionSeconds: StateFlow<Double>
    val durationSeconds: StateFlow<Double>
    val bufferedSeconds: StateFlow<Double>
    val playbackRate: StateFlow<Double>
    val volume: StateFlow<Float> // 0.0f to 1.0f
    val errorMessage: StateFlow<String?>

    val audioTracks: StateFlow<List<PlayerTrack>>
    val subtitleTracks: StateFlow<List<PlayerTrack>>

    suspend fun load(
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionSeconds: Double = 0.0,
        hardwareDecoding: Boolean = true,
    )

    fun play()
    fun pause()
    fun togglePlay()
    fun seek(positionSeconds: Double)
    fun setRate(rate: Double)
    fun setVolume(volume: Float)
    fun selectTrack(kind: TrackKind, trackId: Int?)
    fun setAspectMode(mode: AspectMode) {}

    /** Tone-map HDR (PQ/HLG) video to SDR. On by default. */
    fun setHdrToneMapping(enabled: Boolean) {}

    /** Audio delay in seconds: positive plays the sound later (mpv `audio-delay`). Kept across loads by the caller. */
    fun setAudioDelay(seconds: Double) {}

    /** Subtitle delay in seconds: positive shows the text later (mpv `sub-delay`). Kept across loads by the caller. */
    fun setSubtitleDelay(seconds: Double) {}

    /**
     * Adds a subtitle file from a link to the current file and selects it (mpv `sub-add`). It shows up in
     * [subtitleTracks] once the engine has read it; the caller waits for that.
     */
    suspend fun addExternalSubtitle(url: String, title: String, language: String?) {}

    fun release()
}

expect fun createDefaultPlayerEngine(): PlayerEngine
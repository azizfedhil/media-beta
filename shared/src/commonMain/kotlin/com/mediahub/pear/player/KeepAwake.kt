package com.mediahub.pear.player

/*
 * Keeping the screen awake during playback (Batch 4.6c). Spec: `updateIdleTimer` in PlayerView.swift:
 * `UIApplication.shared.isIdleTimerDisabled = !model.isPaused && model.error == nil`.
 */

/** Holds off the screen saver, the screen lock and sleep while [setAwake] is true. */
interface KeepAwake {
    fun setAwake(awake: Boolean)

    /** Gives the system's own timers back. Safe to call more than once. */
    fun release()
}

/** A platform that can't keep the screen awake. */
object NoKeepAwake : KeepAwake {
    override fun setAwake(awake: Boolean) {}

    override fun release() {}
}

/** The keep-awake for this platform. Desktop: Windows execution state, a systemd inhibitor on Linux. */
expect fun createKeepAwake(): KeepAwake

object KeepAwakeRules {
    /**
     * Awake while the stream opens, buffers or plays, and not while paused, after the end, or after a failure. The iOS
     * idle timer reads "not paused and no error", which is the same thing for a stream that is opening.
     */
    fun wanted(state: PlaybackState, error: String?): Boolean {
        if (error != null) return false
        return when (state) {
            PlaybackState.Loading, PlaybackState.Buffering, PlaybackState.Playing -> true
            PlaybackState.Idle, PlaybackState.Paused, PlaybackState.Ended, PlaybackState.Error -> false
        }
    }
}

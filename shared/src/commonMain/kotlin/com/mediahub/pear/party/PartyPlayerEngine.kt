package com.mediahub.pear.party

import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.PlayerEngine

/**
 * The engine a player's controls drive while a watch party is on (batch K5). Play, pause, seek and speed are the ones a room
 * decides, so they go to [onPlay], [onPause], [onSeek] and [onRate]. Everything else (the picture, tracks, volume, the clock)
 * is the engine's own. The room's adapters keep the raw engine, so they are not caught here.
 */
class PartyPlayerEngine(
    private val inner: PlayerEngine,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSeek: (Double) -> Unit,
    private val onRate: (Double) -> Unit,
) : PlayerEngine by inner {
    override fun play() = onPlay()

    override fun pause() = onPause()

    override fun togglePlay() {
        if (inner.state.value == PlaybackState.Playing) onPause() else onPlay()
    }

    override fun seek(positionSeconds: Double) = onSeek(positionSeconds)

    override fun setRate(rate: Double) = onRate(rate)
}

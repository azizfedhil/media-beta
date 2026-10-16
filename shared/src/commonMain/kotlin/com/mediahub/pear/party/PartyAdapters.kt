package com.mediahub.pear.party

import com.mediahub.pear.player.PlaybackState
import com.mediahub.pear.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Seconds between the host's `sync` messages while playing (cloud/CLOUD_API.md §4). */
private const val HOST_SYNC_EVERY_MS = 15_000L

/** Seconds between a guest's drift checks while the room is set. */
private const val GUEST_DRIFT_EVERY_MS = 1_000L

/**
 * The host's side of a room. The host's player is the room's clock: this adapter sends what the host's player does,
 * and follows a room change the host did not make (an auto-approved request, or a resolve the room applied).
 *
 * The host's own commands are remembered in [lastSentPlaying], so the room's echo of them is not applied twice.
 * Nothing here loads a stream: the app loads it, then calls [loaded] so the room learns the source.
 */
class PartyHostAdapter(
    private val engine: PlayerEngine,
    private val client: PartyClient,
    private val scope: CoroutineScope,
) {
    private var lastSentPlaying = false
    private var appliedRev = -1L
    private val jobs = ArrayList<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { engine.state.collect { onEngineState(it) } }
        jobs += scope.launch { client.session.state.collect { onRoom(it) } }
        jobs += scope.launch { syncLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** The host loaded [source] in its player: the room learns it, and the room starts paused from [pos]. */
    fun loaded(source: PartySource, pos: Double = 0.0) {
        lastSentPlaying = false
        client.load(source, pos)
        // The engine may be playing already: the room was not ready when it started. Tell the room now, not on the next change.
        if (engine.state.value == PlaybackState.Playing) {
            lastSentPlaying = true
            client.play(engine.positionSeconds.value)
        }
    }

    /** The host seeks: the player and the room both move. */
    fun seek(pos: Double) {
        engine.seek(pos)
        client.seek(pos)
    }

    /** The host changes speed: the player and the room both change. */
    fun setRate(rate: Double) {
        engine.setRate(rate)
        client.rate(rate)
    }

    /**
     * The host leaves the player without ending the room. The room pauses, so the guests are not left playing alone
     * (cloud/CLOUD_API.md §5).
     */
    fun pauseForLeave() {
        val pos = engine.positionSeconds.value
        lastSentPlaying = false
        engine.pause()
        client.pause(pos)
    }

    private fun onEngineState(st: PlaybackState) {
        val playing = when (st) {
            PlaybackState.Playing -> true
            PlaybackState.Paused, PlaybackState.Ended -> false
            // Loading, buffering and errors are not the host's choice, so the room is not told about them.
            else -> return
        }
        if (playing == lastSentPlaying) return
        lastSentPlaying = playing
        val pos = engine.positionSeconds.value
        if (playing) client.play(pos) else client.pause(pos)
    }

    private fun onRoom(s: PartySessionState) {
        val room = s.state ?: return
        if (room.rev <= appliedRev) return
        appliedRev = room.rev
        if (room.playing == lastSentPlaying) return
        // Not the host's own command: an auto-approved request, or a resolve the room applied. Follow it.
        lastSentPlaying = room.playing
        if (room.playing) engine.play() else engine.pause()
    }

    private suspend fun syncLoop() {
        while (true) {
            delay(HOST_SYNC_EVERY_MS)
            if (engine.state.value == PlaybackState.Playing) client.sync(engine.positionSeconds.value)
        }
    }
}

/**
 * The guest's side of a room. The room's state is applied to this device's own player: play and pause follow `playing`,
 * the room's speed is set, and the position is kept within the drift rules (see [DriftController]).
 *
 * The source is not this adapter's business: when the room's source changes, [onSourceChanged] runs. The app resolves the
 * stream on the guest's own add-ons, loads it, and then calls [sourceLoaded]. Until then the adapter leaves the player alone.
 */
class PartyGuestAdapter(
    private val engine: PlayerEngine,
    private val client: PartyClient,
    private val scope: CoroutineScope,
    /** False when the engine cannot change speed: drift is then corrected by seeking. */
    private val canChangeRate: Boolean = true,
) {
    /** The room's source changed (null when it was cleared). Resolve and load it, then call [sourceLoaded]. */
    var onSourceChanged: ((PartySource?) -> Unit)? = null

    private var loaded = false
    private var appliedSource: PartySource? = null
    private var appliedRev = -1L
    private var appliedPlaying: Boolean? = null
    private var appliedRate: Double? = null
    private var sentReady: Boolean? = null
    private var lastEngine: PlaybackState? = null
    private val jobs = ArrayList<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { client.session.state.collect { onRoom(it) } }
        jobs += scope.launch { engine.state.collect { onEngineState(it) } }
        jobs += scope.launch { driftLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        loaded = false
    }

    /** The app has loaded the room's source in this device's player. The room's state is applied from now on. */
    fun sourceLoaded() {
        loaded = true
        appliedRev = -1L
        appliedPlaying = null
        // A guest that had no source (ready false) is ready again now its stream is open.
        sendReady(true)
        onRoom(client.session.state.value)
    }

    /** The room's source could not be found on this device: the host sees this guest as not ready (batch K6). */
    fun sourceUnavailable() = sendReady(false)

    private fun onRoom(s: PartySessionState) {
        val room = s.state ?: return
        if (room.source != appliedSource) {
            appliedSource = room.source
            loaded = false
            onSourceChanged?.invoke(room.source)
            return
        }
        if (!loaded) return
        if (room.rate != appliedRate) {
            appliedRate = room.rate
            if (canChangeRate) engine.setRate(room.rate)
        }
        if (room.rev <= appliedRev) return
        appliedRev = room.rev

        // Any new state moves the player to where the room is now: a seek, a load or a play and pause all land here.
        val target = DriftController.targetPosition(room, client.serverNowMs())
        val drift = target - engine.positionSeconds.value
        val changedPlaying = appliedPlaying != room.playing
        appliedPlaying = room.playing
        if (abs(drift) >= DriftRules.IGNORE_BELOW) engine.seek(target)
        if (changedPlaying) {
            if (room.playing) engine.play() else engine.pause()
        }
    }

    private fun onEngineState(st: PlaybackState) {
        val previous = lastEngine
        lastEngine = st
        // Buffering while the room plays tells the host who is holding it back (ready false, then true).
        if (st == PlaybackState.Buffering && previous == PlaybackState.Playing) sendReady(false)
        if (st == PlaybackState.Playing && previous == PlaybackState.Buffering) sendReady(true)
    }

    private fun sendReady(ready: Boolean) {
        if (sentReady == ready) return
        sentReady = ready
        client.ready(ready)
    }

    private suspend fun driftLoop() {
        while (true) {
            delay(GUEST_DRIFT_EVERY_MS)
            if (!loaded) continue
            val room = client.session.state.value.state ?: continue
            val target = DriftController.targetPosition(room, client.serverNowMs())
            val drift = target - engine.positionSeconds.value
            if (!room.playing) {
                // Paused in the room: only a real gap is corrected, and speed does not matter.
                if (abs(drift) >= DriftRules.IGNORE_BELOW) engine.seek(target)
                continue
            }
            when (val action = DriftController.action(drift, engine.playbackRate.value, room.rate, canChangeRate)) {
                DriftAction.Ignore -> Unit
                is DriftAction.Nudge -> engine.setRate(action.rate)
                DriftAction.RestoreRate -> engine.setRate(room.rate)
                DriftAction.Seek -> engine.seek(target)
            }
        }
    }
}

package com.mediahub.pear.p2p

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's handle on the P2P engine (spec: `P2PManager` in P2PManager.swift).
 *
 * The engine is created by the first [open] and exists until [stop]: nothing runs before a stream is asked for, and
 * stopping closes every socket, joins the engine's threads and deletes the cached pieces. Only one stream is open at a
 * time. [stop] does not wait behind a slow [open] (a metadata fetch): it cancels it, as the engine does.
 *
 * Every call that reaches the engine runs on [CoroutineDispatcher] given by the platform, never the caller's thread.
 */
class P2PEngine(private val platform: P2PPlatform) {
    private val dispatcher: CoroutineDispatcher = platform.blockingDispatcher
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards [native] and [generation]. Held for moments only, never across an engine call. */
    private val state = Mutex()

    /** One [open] at a time: a second one waits for the first rather than racing it. */
    private val openGate = Mutex()

    private var native: P2PNative? = null

    /** Bumped by every [stop]; an [open] that finds it changed was stopped while it was waiting on the engine. */
    private var generation = 0

    /**
     * Starts the engine if it isn't running, resolves the torrent and returns the loopback stream. Suspends for as long as
     * the torrent's metadata takes (up to the engine's timeout). Throws [P2PFailure].
     */
    suspend fun open(source: P2PSource): P2PStream = openGate.withLock {
        val (engine, gen) = state.withLock { native to generation }
        val ready = engine ?: createEngine(gen)
        withContext(dispatcher) { ready.startStream(source) }
    }

    /** Ends the open stream. The engine stays loaded and idle. Idempotent. */
    suspend fun stopStream() {
        val engine = state.withLock { native } ?: return
        withContext(dispatcher) { engine.stopStream() }
    }

    /** The engine's state for a status line. Cheap; does not wait for an open in progress. */
    suspend fun status(): P2PStatus {
        val engine = state.withLock { native }
        if (engine == null) return P2PStatus(engineLoaded = false, stats = null)
        return withContext(dispatcher) { P2PStatus(engineLoaded = engine.isRunning(), stats = engine.stats()) }
    }

    /** The killswitch. Ends any stream, closes the engine and deletes its cached data. Idempotent. */
    suspend fun stop() {
        val engine = state.withLock {
            generation++
            native.also { native = null }
        } ?: return
        withContext(dispatcher) {
            engine.stop()
            engine.close()
        }
    }

    /** [stop] without waiting, for a caller that is going away (a screen closing, the app quitting). */
    fun requestStop() {
        scope.launch { stop() }
    }

    /** `p2p-*` folders left behind by an earlier crash or kill, for the status line. */
    fun leftoverCacheEntries(): Int = platform.leftoverCacheEntries()

    /** Creates the engine, unless a stop arrived while it was being created: then it is closed and the open is cancelled. */
    private suspend fun createEngine(gen: Int): P2PNative {
        val created = withContext(dispatcher) { platform.createNative(platform.cacheDir) }
        val stopped = state.withLock {
            if (generation != gen) {
                true
            } else {
                native = created
                false
            }
        }
        if (stopped) {
            withContext(dispatcher) {
                created.stop()
                created.close()
            }
            throw P2PFailure.Cancelled
        }
        return created
    }
}

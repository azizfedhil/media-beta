package com.mediahub.pear.p2p

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The Rust engine as the app sees it: the calls of `P2pEngine` in p2p-core. Every call here blocks (a torrent's metadata
 * can take tens of seconds), so they run off the UI thread, through [P2PEngine].
 */
interface P2PNative {
    /** Resolves the torrent and opens its file as a loopback stream. Throws [P2PFailure]. */
    fun startStream(source: P2PSource): P2PStream

    /** Ends the open stream and deletes its files. The engine stays loaded and idle. */
    fun stopStream()

    /** The open stream's numbers, or null when none is open. */
    fun stats(): P2PStats?

    fun isRunning(): Boolean

    /** Low Power Mode and thermal pressure: a slower upload and a smaller read-ahead. */
    fun setPowerSaving(on: Boolean)

    /** Killswitch: closes every socket, joins the engine's threads and deletes the cached data. Idempotent. */
    fun stop()

    /** Releases the native object. Call after [stop]. */
    fun close()
}

/**
 * What the platform provides for the engine: where its files go, the thread its blocking calls run on, and how to load
 * the engine. The engine is created only when a stream is first asked for, so none of this runs at launch.
 */
interface P2PPlatform {
    /** The folder the engine keeps its per-session `p2p-*` folders in. */
    val cacheDir: String

    /** Where the blocking calls run: a pool that can wait, not the UI thread. */
    val blockingDispatcher: CoroutineDispatcher

    /** Loads the library and creates the engine in [cacheDir]. Throws [P2PFailure.Unavailable] when the library is missing. */
    fun createNative(cacheDir: String): P2PNative

    /** `p2p-*` folders left in [cacheDir] (a crash or a kill can leave some; a clean stop leaves none). */
    fun leftoverCacheEntries(): Int
}

/** The platform's engine support. Desktop: `desktopMain/p2p`. */
expect fun p2pPlatform(): P2PPlatform

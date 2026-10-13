package com.mediahub.pear.p2p

import com.mediahub.pear.data.AppDirs
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import uniffi.p2p_core.P2pEngine
import uniffi.p2p_core.P2pException
import uniffi.p2p_core.defaultEngineConfig

/** Desktop: the engine is the UniFFI-generated binding of p2p-core, in its own folder under the app's cache. */
internal object DesktopP2PPlatform : P2PPlatform {
    override val cacheDir: String = File(AppDirs.detect().cache, "P2P").absolutePath

    override val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun createNative(cacheDir: String): P2PNative {
        val searched = mutableListOf<String>()
        val lib = P2PLibrary.locate(searched) ?: throw P2PFailure.Unavailable(P2PLibrary.describeMissing())
        // Before the first binding call: the bindings read this once, when they first load the library.
        P2PLibrary.point(lib)
        val engine = try {
            P2pEngine(defaultEngineConfig(cacheDir))
        } catch (e: P2pException) {
            throw e.toFailure()
        } catch (e: UnsatisfiedLinkError) {
            throw P2PFailure.Unavailable("The P2P engine library would not load (${lib.absolutePath}): ${e.message}")
        }
        return UniffiP2PNative(engine)
    }

    override fun leftoverCacheEntries(): Int =
        File(cacheDir).listFiles()?.count { it.name.startsWith("p2p-") } ?: 0

    override fun removeLeftovers(): Int {
        val leftovers = File(cacheDir).listFiles()?.filter { it.name.startsWith("p2p-") } ?: return 0
        leftovers.forEach { it.deleteRecursively() }
        return leftovers.size
    }
}

actual fun p2pPlatform(): P2PPlatform = DesktopP2PPlatform

/** The generated engine, behind the app's interface. Its errors and numbers are translated here, and nowhere else. */
private class UniffiP2PNative(private val engine: P2pEngine) : P2PNative {
    override fun startStream(source: P2PSource): P2PStream = guarded {
        val info = engine.startStream(source.infoHash, source.trackers, source.fileIdx?.toUInt())
        P2PStream(url = info.url, fileName = info.fileName, length = info.length.toLong(), fileIndex = info.fileIndex.toInt())
    }

    override fun stopStream() = guarded { engine.stopStream() }

    override fun stats(): P2PStats? = guarded {
        engine.stats()?.let {
            P2PStats(
                downloadedBytes = it.downloadedBytes.toLong(),
                uploadedBytes = it.uploadedBytes.toLong(),
                totalBytes = it.totalBytes.toLong(),
                peersLive = it.peersLive.toInt(),
                downloadBps = it.downloadBps.toLong(),
            )
        }
    }

    override fun isRunning(): Boolean = guarded { engine.isRunning() }

    override fun setPowerSaving(on: Boolean) = guarded { engine.setPowerSaving(on) }

    override fun stop() = guarded { engine.stop() }

    override fun close() = engine.close()

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: P2pException) {
            throw e.toFailure()
        }
}

/** The engine's errors as the app's failures (spec: `P2PManager.map`). */
private fun P2pException.toFailure(): P2PFailure = when (this) {
    is P2pException.MetadataTimeout -> P2PFailure.NoPeers
    is P2pException.NoFile -> P2PFailure.NoVideo
    is P2pException.Stopped -> P2PFailure.Cancelled
    else -> P2PFailure.Engine(message ?: "unknown error")
}

package com.mediahub.pear.p2p

// The UniFFI engine behind the app's P2P interface. Shared by desktop and Android (the bindings are the same).
import uniffi.p2p_core.P2pEngine
import uniffi.p2p_core.P2pException

/** The generated engine, behind the app's interface. Its errors and numbers are translated here, and nowhere else. */
internal class UniffiP2PNative(private val engine: P2pEngine) : P2PNative {
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

    override fun setPerformanceMode(on: Boolean) = guarded { engine.setPerformanceMode(on) }

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
internal fun P2pException.toFailure(): P2PFailure = when (this) {
    is P2pException.MetadataTimeout -> P2PFailure.NoPeers
    is P2pException.NoFile -> P2PFailure.NoVideo
    is P2pException.Stopped -> P2PFailure.Cancelled
    else -> P2PFailure.Engine(message ?: "unknown error")
}

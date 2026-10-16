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

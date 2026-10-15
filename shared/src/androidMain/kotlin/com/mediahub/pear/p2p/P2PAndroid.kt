package com.mediahub.pear.p2p

import com.mediahub.pear.data.AndroidDirs
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import uniffi.p2p_core.P2pEngine
import uniffi.p2p_core.P2pException
import uniffi.p2p_core.defaultEngineConfig

/**
 * Android: the engine is the same UniFFI binding as desktop. The Rust library sits in jniLibs (built by androidApp's
 * buildP2pCoreAndroid), and JNA finds it there by name, so there is nothing to locate.
 */
private object AndroidP2PPlatform : P2PPlatform {
    override val cacheDir: String
        get() = AndroidDirs.p2pCache().absolutePath

    override val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun createNative(cacheDir: String): P2PNative {
        val engine = try {
            P2pEngine(defaultEngineConfig(cacheDir))
        } catch (e: P2pException) {
            throw e.toFailure()
        } catch (e: UnsatisfiedLinkError) {
            // The build did not include the library (see buildP2pCoreAndroid in androidApp/build.gradle.kts).
            throw P2PFailure.Unavailable("The P2P engine is not in this build (${e.message}).")
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

actual fun p2pPlatform(): P2PPlatform = AndroidP2PPlatform

package com.mediahub.pear.p2p

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A stand-in for the native engine: counts what it was asked to do, so the wrapper's lifecycle can be checked. */
private class FakeNative : P2PNative {
    var streams = 0
    var stopped = false
    var closed = false

    override fun startStream(source: P2PSource): P2PStream {
        streams++
        return P2PStream("http://127.0.0.1:1/token/stream/a.mkv", "a.mkv", 100, 0)
    }

    override fun stopStream() {}
    override fun stats(): P2PStats? = null
    override fun isRunning(): Boolean = !stopped
    override fun setPowerSaving(on: Boolean) {}
    override fun stop() { stopped = true }
    override fun close() { closed = true }
}

private class FakePlatform : P2PPlatform {
    override val cacheDir: String = "cache"
    override val blockingDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    val created = mutableListOf<FakeNative>()
    var failure: P2PFailure? = null

    override fun createNative(cacheDir: String): P2PNative {
        failure?.let { throw it }
        return FakeNative().also { created.add(it) }
    }

    override fun leftoverCacheEntries(): Int = 0
}

private val source = P2PSource("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", listOf("udp://t:1/announce"))

class P2PEngineTest {

    @Test
    fun noEngineIsCreatedUntilAStreamIsAsked() = runTest {
        val platform = FakePlatform()
        val engine = P2PEngine(platform)
        assertFalse(engine.status().engineLoaded)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun theFirstOpenCreatesTheEngineAndLaterOpensReuseIt() = runTest {
        val platform = FakePlatform()
        val engine = P2PEngine(platform)
        engine.open(source)
        assertTrue(engine.status().engineLoaded)
        engine.open(source)
        assertEquals(1, platform.created.size)
        assertEquals(2, platform.created.single().streams)
    }

    @Test
    fun stopClosesTheEngineAndTheNextOpenStartsAFreshOne() = runTest {
        val platform = FakePlatform()
        val engine = P2PEngine(platform)
        engine.open(source)
        engine.stop()
        assertFalse(engine.status().engineLoaded)
        assertTrue(platform.created.single().stopped)
        assertTrue(platform.created.single().closed)
        engine.open(source)
        assertEquals(2, platform.created.size)
    }

    @Test
    fun stopIsSafeWhenNothingIsRunningAndWhenCalledTwice() = runTest {
        val platform = FakePlatform()
        val engine = P2PEngine(platform)
        engine.stop()
        engine.open(source)
        engine.stop()
        engine.stop()
        assertTrue(platform.created.single().closed)
    }

    @Test
    fun aLibraryThatIsMissingSurfacesAsUnavailable() = runTest {
        val platform = FakePlatform().apply { failure = P2PFailure.Unavailable("the library is missing") }
        val engine = P2PEngine(platform)
        assertFailsWith<P2PFailure.Unavailable> { engine.open(source) }
        assertFalse(engine.status().engineLoaded)
    }

    @Test
    fun removingLeftoversNeverLoadsTheEngine() = runTest {
        val platform = FakePlatform()
        val engine = P2PEngine(platform)
        assertEquals(0, engine.removeLeftovers())
        assertTrue(platform.created.isEmpty())
    }
}

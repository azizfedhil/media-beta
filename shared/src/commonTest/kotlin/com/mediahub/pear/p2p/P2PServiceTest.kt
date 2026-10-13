package com.mediahub.pear.p2p

import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.StreamItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A network check the test can change. */
private class ServiceNetwork(initial: Boolean) : P2PNetworkWatch {
    val state = MutableStateFlow(initial)
    var readyCalls = 0
    override val allowed: StateFlow<Boolean> = state.asStateFlow()
    override suspend fun ready() {
        readyCalls++
    }
}

/** A stand-in engine, so the switch's effect on it can be checked. */
private class ServiceNative : P2PNative {
    var closed = false
    override fun startStream(source: P2PSource): P2PStream = P2PStream("http://127.0.0.1:1/t/stream/a", "a", 1, 0)
    override fun stopStream() {}
    override fun stats(): P2PStats? = null
    override fun isRunning(): Boolean = !closed
    override fun setPowerSaving(on: Boolean) {}
    override fun stop() {}
    override fun close() { closed = true }
}

private class ServicePlatform : P2PPlatform {
    override val cacheDir: String = "cache"
    override val blockingDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    val natives = mutableListOf<ServiceNative>()
    override fun createNative(cacheDir: String): P2PNative = ServiceNative().also { natives.add(it) }
    override fun leftoverCacheEntries(): Int = 0
}

private fun torrent(name: String) = StreamItem(name = name, infoHash = "a".repeat(40))
private fun direct(name: String) = StreamItem(name = name, url = "https://example.com/$name.mp4")

class P2PServiceTest {

    private fun service(
        prefs: PrefStore = PrefStore(),
        network: ServiceNetwork = ServiceNetwork(true),
        platform: ServicePlatform = ServicePlatform(),
    ) = P2PService(prefs, P2PEngine(platform), network, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun p2pIsOffUntilTheUserTurnsItOn() {
        val s = service()
        assertFalse(s.enabled.value)
        assertFalse(s.canStream.value)
        assertTrue(s.wifiOnly.value, "Wi-Fi only defaults to on, as on iOS")
    }

    @Test
    fun turningItOnMakesTorrentsPlayableAtOnce() {
        val s = service()
        s.setEnabled(true)
        assertTrue(s.canStream.value)
        assertTrue(s.canStreamNow())
    }

    @Test
    fun aNetworkThatIsNotAllowedBlocksTorrentsEvenWhenTheSwitchIsOn() {
        val network = ServiceNetwork(false)
        val s = service(network = network)
        s.setEnabled(true)
        assertFalse(s.canStream.value)
        network.state.value = true
        assertTrue(s.canStream.value, "a change of network takes effect at once")
    }

    @Test
    fun theSwitchOffKeepsAStreamThatIsAlreadyPlaying() = runTest {
        val platform = ServicePlatform()
        val s = service(platform = platform)
        s.setEnabled(true)
        s.engine.open(P2PSource("a".repeat(40)))
        s.setEnabled(false)
        assertFalse(s.canStream.value, "new torrent sources stop playing at once")
        assertTrue(s.engine.status().engineLoaded, "the stream being watched carries on")
        assertFalse(platform.natives.single().closed)
        s.engine.stop() // what leaving the stream does
        assertFalse(s.engine.status().engineLoaded)
    }

    @Test
    fun theSettingsAreSavedForTheNextLaunch() {
        val prefs = PrefStore()
        service(prefs = prefs).apply {
            setEnabled(true)
            setWifiOnly(false)
        }
        val relaunched = service(prefs = prefs)
        assertTrue(relaunched.enabled.value)
        assertFalse(relaunched.wifiOnly.value)
    }

    @Test
    fun theGateKeepsEveryStreamAndWaitsForTheNetworkOnlyWhenOn() = runTest {
        val network = ServiceNetwork(true)
        val s = service(network = network)
        val streams = listOf(direct("a"), torrent("b"))

        assertEquals(streams, s.gate(streams), "a torrent stays in the list while P2P is off, so the list can say why")
        assertEquals(0, network.readyCalls)

        s.setEnabled(true)
        assertEquals(streams, s.gate(streams))
        assertEquals(1, network.readyCalls)
    }
}

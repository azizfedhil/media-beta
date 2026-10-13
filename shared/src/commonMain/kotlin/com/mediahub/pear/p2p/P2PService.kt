package com.mediahub.pear.p2p

import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The app's P2P switches and what they allow (spec: `P2PSettings`, `P2PGate`, and the killswitch in `P2PManager`).
 *
 * Off by default. The switch is the killswitch: turning it off makes every torrent source unplayable at once, and stops the
 * engine. The settings are the device's own and are not in a settings backup, so restoring a backup can't turn P2P on.
 */
class P2PService(
    private val prefs: PrefStore,
    /** The app's one engine. It is created by the first stream, not here. */
    val engine: P2PEngine = P2PEngine(p2pPlatform()),
    private val network: P2PNetworkWatch = p2pNetwork(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _enabled = MutableStateFlow(prefs.bool(PrefKeys.P2P_ENABLED, false))

    /** The Settings switch. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.bool(PrefKeys.P2P_WIFI_ONLY, true))

    /** Wi-Fi only. On a desktop this is kept but has no effect (see [P2PNetworkWatch]). */
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** On, and the network allows it: torrent sources can be played. A change to either takes effect at once. */
    val canStream: StateFlow<Boolean> = combine(_enabled, network.allowed) { on, allowed -> on && allowed }
        .stateIn(scope, SharingStarted.Eagerly, canStreamNow())

    fun canStreamNow(): Boolean = _enabled.value && network.allowed.value

    /** The switch. Off ends any stream and closes the engine. */
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.setBool(PrefKeys.P2P_ENABLED, on)
        if (!on) engine.requestStop()
    }

    fun setWifiOnly(on: Boolean) {
        _wifiOnly.value = on
        prefs.setBool(PrefKeys.P2P_WIFI_ONLY, on)
    }

    /**
     * The add-on client's filter (spec: `P2PGate.apply`). It keeps every stream, torrents included, so the list can show a
     * torrent as unavailable and say why. With P2P on, it first waits for the network check, as iOS does.
     */
    suspend fun gate(streams: List<StreamItem>): List<StreamItem> {
        if (_enabled.value && streams.any { it.isTorrent }) network.ready()
        return streams
    }
}

package com.mediahub.pear.p2p

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the current network may carry P2P traffic (spec: `P2PNetwork`). On a phone, Wi-Fi only excludes cellular and
 * personal hotspots. A desktop can't tell those apart, so its check always allows (see the desktop actual).
 */
interface P2PNetworkWatch {
    val allowed: StateFlow<Boolean>

    /** Waits a moment for the first answer, so a fresh launch doesn't read as offline. */
    suspend fun ready()
}

/** The platform's network check. [wifiOnly] is the Settings switch: on Android it limits P2P to unmetered Wi-Fi and Ethernet. */
expect fun p2pNetwork(wifiOnly: StateFlow<Boolean>): P2PNetworkWatch

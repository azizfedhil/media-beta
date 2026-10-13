package com.mediahub.pear.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop: there is no reliable way to tell Wi-Fi from a hotspot or a metered link, so the network is always allowed. The
 * Wi-Fi only setting is kept, and Settings says that it doesn't limit P2P on this computer.
 */
private object DesktopP2PNetwork : P2PNetworkWatch {
    override val allowed: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override suspend fun ready() {}
}

actual fun p2pNetwork(): P2PNetworkWatch = DesktopP2PNetwork

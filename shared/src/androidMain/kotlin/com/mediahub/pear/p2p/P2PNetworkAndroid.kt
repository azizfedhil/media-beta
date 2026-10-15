package com.mediahub.pear.p2p

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mediahub.pear.AndroidHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android: the network check (spec: `P2PNetwork`). With Wi-Fi only, P2P runs on an unmetered Wi-Fi or Ethernet network: not on
 * cellular, and not on a hotspot (a hotspot is metered). The default network is watched, so a change applies at once.
 */
private class AndroidP2PNetwork(wifiOnly: StateFlow<Boolean>) : P2PNetworkWatch {
    // Declared before init, which registers the callback that fills them.
    /** The default network is Wi-Fi or Ethernet, and not metered. False until the system says otherwise. */
    private val onGoodNetwork = MutableStateFlow(false)

    /** The system has answered at least once. [ready] waits for this. */
    private val answered = CompletableDeferred<Unit>()

    /** Fails closed: with Wi-Fi only and no answer yet, P2P is not allowed. */
    private val allowedNow = MutableStateFlow(!wifiOnly.value)
    override val allowed: StateFlow<Boolean> = allowedNow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            onGoodNetwork.value = isGood(capabilities)
            answered.complete(Unit)
        }

        override fun onLost(network: Network) {
            onGoodNetwork.value = false
            answered.complete(Unit)
        }
    }

    init {
        scope.launch {
            combine(wifiOnly, onGoodNetwork) { only, good -> !only || good }.collect { allowedNow.value = it }
        }
        AndroidHost.app().getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback)
    }

    override suspend fun ready() {
        withTimeoutOrNull(READY_WAIT_MILLIS) { answered.await() }
    }

    private fun isGood(capabilities: NetworkCapabilities): Boolean =
        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    private companion object {
        /** A fresh launch waits this long for the first answer, so it doesn't read as offline. */
        const val READY_WAIT_MILLIS = 3_000L
    }
}

actual fun p2pNetwork(wifiOnly: StateFlow<Boolean>): P2PNetworkWatch = AndroidP2PNetwork(wifiOnly)

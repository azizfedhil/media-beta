package com.mediahub.pear.cloud

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The OkHttp client for party sockets. Its pings keep an idle WebSocket open through NAT and sleep. The room's own `ping`
 * messages (cloud/CLOUD_API.md section 4) are sent by the protocol layer, not here.
 */
private val partyHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
}

/** A party socket on OkHttp, for desktop and Android. */
fun okHttpPartySocket(): PartySocket = OkHttpPartySocket(partyHttp)

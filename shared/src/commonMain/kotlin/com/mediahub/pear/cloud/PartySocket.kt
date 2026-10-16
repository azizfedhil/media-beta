package com.mediahub.pear.cloud

import kotlinx.coroutines.flow.Flow

/** One thing a [PartySocket] reports, in the order it happened. */
sealed interface SocketEvent {
    /** One text frame. The protocol layer parses it. */
    data class Text(val text: String) : SocketEvent

    /** The socket closed. [code] is the close code: 1000 for a normal close, or 3000 to 4999 for the room's own codes. */
    data class Closed(val code: Int, val reason: String) : SocketEvent

    /** The socket failed after it opened (network lost, bad frame). Nothing follows except the end of [PartySocket.incoming]. */
    data class Failed(val message: String) : SocketEvent
}

/**
 * One WebSocket connection to a watch party room. A socket is used once: [connect], then [send] and [close], while
 * collecting [incoming] in one place. The platform implementation is `OkHttpPartySocket` (jvmCommonMain).
 */
interface PartySocket {
    /**
     * Opens the connection and returns once the server has accepted the upgrade. Throws [CloudError.Network] when it can't.
     * [url] is a `https://` or `wss://` address that already has the room code in it.
     */
    suspend fun connect(url: String)

    /** Sends one text frame. Returns false when the socket is not open; the caller then reconnects. */
    fun send(text: String): Boolean

    /** Events in order. Collect it once: it completes after [SocketEvent.Closed] or [SocketEvent.Failed]. */
    val incoming: Flow<SocketEvent>

    /** Starts a close with [code] (1000, or 3000 to 4999) and [reason]. A [SocketEvent.Closed] event follows. */
    fun close(code: Int = 1000, reason: String = "")
}

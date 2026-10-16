package com.mediahub.pear.cloud

import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * [PartySocket] on OkHttp's WebSocket, for desktop and Android. Pass the app's OkHttp client so the connection pool and
 * proxy settings are shared. This class sends no pings of its own: the party's `ping` messages come from the protocol
 * layer, at the cadence in cloud/CLOUD_API.md section 4.
 */
class OkHttpPartySocket(private val client: OkHttpClient) : PartySocket {
    private val events = Channel<SocketEvent>(Channel.UNLIMITED)

    @Volatile
    private var socket: WebSocket? = null

    override val incoming: Flow<SocketEvent> = events.receiveAsFlow()

    override suspend fun connect(url: String) {
        check(socket == null) { "A PartySocket connects once. Make a new one to reconnect." }
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    events.trySend(SocketEvent.Text(text))
                }

                // The server started the close: answer it, or OkHttp never reports onClosed.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, "")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.trySend(SocketEvent.Closed(code, reason))
                    events.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val message = t.message ?: "The connection failed."
                    if (!opened.isCompleted) {
                        // Never opened: the caller of connect() gets the error.
                        opened.completeExceptionally(CloudError.Network(message, t))
                    } else {
                        events.trySend(SocketEvent.Failed(message))
                    }
                    events.close()
                }
            },
        )
        socket = ws
        try {
            opened.await()
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        }
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    // Valid codes are 1000 and 3000 to 4999. Other values make OkHttp throw, so callers must pass one of those.
    override fun close(code: Int, reason: String) {
        socket?.close(code, reason)
    }
}

package com.mediahub.pear.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * [Http] on a Ktor client. The client must have the `HttpTimeout` plugin installed (the desktop one does).
 * Redirects are followed and compressed answers are unpacked by the engine.
 */
class KtorHttp(
    private val client: HttpClient,
    private val userAgent: String = "Pear/1.0",
) : Http {
    override suspend fun execute(request: HttpRequest): HttpReply {
        try {
            val contentTypeHeader = request.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                for ((k, v) in request.headers) {
                    if (!k.equals("Content-Type", ignoreCase = true)) header(k, v)
                }
                if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header(HttpHeaders.UserAgent, userAgent)
                val body = request.body
                if (body != null) {
                    contentType(contentTypeHeader?.let { ContentType.parse(it) } ?: ContentType.Application.Json)
                    setBody(body)
                }
                timeout {
                    requestTimeoutMillis = request.timeoutMillis
                    connectTimeoutMillis = minOf(request.timeoutMillis, 15_000L)
                    socketTimeoutMillis = request.timeoutMillis
                }
            }
            val headers = HashMap<String, String>()
            for (name in response.headers.names()) response.headers[name]?.let { headers[name.lowercase()] = it }
            return HttpReply(response.status.value, response.bodyAsBytes(), headers)
        } catch (e: CancellationException) {
            // A timeout surfaces as a cancellation. If our own coroutine is still alive it was not us who was
            // cancelled, so it is a network failure; if we were cancelled, ensureActive() throws the right thing.
            currentCoroutineContext().ensureActive()
            throw NetworkException("The request was interrupted", e)
        } catch (e: Exception) {
            throw NetworkException(e.message ?: "Network error", e)
        }
    }

    override fun close() = client.close()
}

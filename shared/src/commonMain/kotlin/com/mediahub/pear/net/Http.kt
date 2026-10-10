package com.mediahub.pear.net

import io.ktor.http.URLBuilder
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one door every metadata client goes through. A small interface (instead of Ktor types everywhere) so the
 * clients are tested with a fake, and so the engine can differ per platform: OkHttp on the desktop (`KtorHttp`).
 */
interface Http {
    /**
     * Sends [request]. Any answer from the server, whatever the status, is a [HttpReply].
     * Throws [NetworkException] when there is no answer (offline, DNS, timeout, TLS...).
     */
    suspend fun execute(request: HttpRequest): HttpReply

    fun close() {}
}

class HttpRequest(
    /** The full address, query string included and already encoded (see [buildUrl]). */
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** Text sent as the body (JSON for the TVDB login). Its type comes from a `Content-Type` header. */
    val body: String? = null,
    val timeoutMillis: Long = 30_000,
)

class HttpReply(val status: Int, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/** Base of everything the metadata clients throw on purpose, so a caller can tell them from programming errors. */
open class MetaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** There is no API key for [service] (and, for TMDB, no built-in proxy either). */
class NoKeyException(val service: String) : MetaException("$service needs an API key")

/** The server answered with something other than 200. */
class BadStatusException(val status: Int) : MetaException("The server answered $status")

/** An id from an add-on made an address that can't be built. Fails that one request, never the app. */
class BadUrlException(cause: Throwable? = null) : MetaException("Not a usable address", cause)

/** The title id can't be mapped (an add-on id like `kitsu:1`, or a title TMDB doesn't know). */
class UnresolvableException(val id: String) : MetaException("No match for $id")

/** No answer from the server. */
class NetworkException(message: String, cause: Throwable? = null) : MetaException(message, cause)

/** What previews and tests use when nothing may touch the network. */
object OfflineHttp : Http {
    override suspend fun execute(request: HttpRequest): HttpReply = throw NetworkException("Offline")
}

/**
 * `base` plus an encoded query string. [base] may already hold a query (TheTVDB paths do); [query] is appended.
 * Throws [BadUrlException] instead of crashing when [base] can't be parsed.
 */
fun buildUrl(base: String, query: Map<String, String> = emptyMap()): String {
    try {
        val builder = URLBuilder(base)
        for ((k, v) in query) builder.parameters.append(k, v)
        return builder.buildString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BadUrlException(e)
    }
}

/**
 * Swift's `try?`: the result, or null when the block fails. Cancellation is never swallowed: a screen that went away
 * must not leave behind a "not found" that was really "stopped asking".
 */
internal inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

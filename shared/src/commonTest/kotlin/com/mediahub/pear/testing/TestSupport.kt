package com.mediahub.pear.testing

import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpReply
import com.mediahub.pear.net.HttpRequest
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An [Http] that answers from a function and remembers what it was asked. Safe to call from several threads. */
class FakeHttp(private val handler: suspend (HttpRequest) -> HttpReply) : Http {
    private val lock = Mutex()
    private val log = ArrayList<HttpRequest>()

    /** Read it after the calls have finished. */
    val requests: List<HttpRequest> get() = log.toList()

    override suspend fun execute(request: HttpRequest): HttpReply {
        lock.withLock { log.add(request) }
        return handler(request)
    }
}

fun reply(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
    HttpReply(status, body.encodeToByteArray(), headers.mapKeys { it.key.lowercase() })

/** Path of a TMDB call without the proxy prefix or the `/3` of the direct API: "/trending/movie/week". */
fun HttpRequest.tmdbPath(): String = Url(url).encodedPath.substringAfter("/functions/v1/tmdb").removePrefix("/3")

fun HttpRequest.urlPath(): String = Url(url).encodedPath

fun HttpRequest.param(name: String): String? = Url(url).parameters[name]

/** Answers each exact TMDB path with its body, and 404 for anything else. */
fun tmdbRouter(vararg routes: Pair<String, String>) = FakeHttp { r ->
    val p = r.tmdbPath()
    routes.firstOrNull { it.first == p }?.let { reply(it.second) } ?: reply("{}", 404)
}

package com.mediahub.pear.net

/** How long answers stay fresh. */
internal object Ttl {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}

/**
 * GET with a disk cache in front, shared by the three clients.
 *
 * - A cached answer younger than the time-to-live is returned without asking the server.
 * - Otherwise the server is asked. Only an answer that [parse]s is kept, so a damaged reply never poisons the cache.
 * - If the server can't be reached (or is failing: 5xx, 429), an older cached answer is better than an error.
 *   A 401/403/404 is never covered up that way.
 * - [ttlMillis] of 0 skips the cache completely (answers that belong to one account).
 */
internal class CachedFetcher(
    private val http: Http,
    private val cache: ResponseCache,
    private val now: () -> Long,
) {
    suspend fun <T> fetch(
        key: String,
        ttlMillis: Long,
        build: suspend () -> HttpRequest,
        parse: (ByteArray) -> T,
    ): T {
        val useCache = ttlMillis > 0
        val hit = if (useCache) attempt { cache.get(key) } else null
        if (hit != null && (now() - hit.storedAtMillis) in 0 until ttlMillis) {
            attempt { parse(hit.body) }?.let { return it }
        }
        val reply = try {
            http.execute(build())
        } catch (e: NetworkException) {
            if (hit != null) attempt { parse(hit.body) }?.let { return it }
            throw e
        }
        if (reply.status == 200) {
            val value = parse(reply.body) // a body that doesn't parse is an error, and is not cached
            if (useCache) attempt { cache.put(key, reply.body, now()) }
            return value
        }
        if (hit != null && (reply.status >= 500 || reply.status == 429)) {
            attempt { parse(hit.body) }?.let { return it }
        }
        throw BadStatusException(reply.status)
    }
}

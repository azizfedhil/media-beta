package com.mediahub.pear.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CachedBody(val body: ByteArray, val storedAtMillis: Long)

/**
 * Answers kept between requests (the role `URLCache` plays in the Swift clients). Keys never contain API keys or
 * tokens. Failures here must never fail a request: a cache that can't read or write is just a miss.
 */
interface ResponseCache {
    suspend fun get(key: String): CachedBody?
    suspend fun put(key: String, body: ByteArray, nowMillis: Long)
    suspend fun clear() {}

    /** One line for the Metadata check screen. */
    suspend fun describe(): String = "no cache"

    companion object {
        val None: ResponseCache = object : ResponseCache {
            override suspend fun get(key: String): CachedBody? = null
            override suspend fun put(key: String, body: ByteArray, nowMillis: Long) {}
        }
    }
}

/** Least-recently-used cache in memory, bounded by the bytes it holds. Used by tests and as a fallback. */
class MemoryResponseCache(private val maxBytes: Long = 10L shl 20) : ResponseCache {
    private val lock = Mutex()
    private val map = LinkedHashMap<String, CachedBody>()
    private var bytes = 0L

    override suspend fun get(key: String): CachedBody? = lock.withLock {
        val hit = map.remove(key)
        if (hit != null) map[key] = hit // most recently used goes last
        hit
    }

    override suspend fun put(key: String, body: ByteArray, nowMillis: Long) {
        lock.withLock {
            map.remove(key)?.let { bytes -= it.body.size }
            map[key] = CachedBody(body, nowMillis)
            bytes += body.size
            while (bytes > maxBytes && map.size > 1) {
                val oldest = map.keys.first()
                bytes -= map.getValue(oldest).body.size
                map.remove(oldest)
            }
        }
    }

    override suspend fun clear() {
        lock.withLock { map.clear(); bytes = 0 }
    }

    override suspend fun describe(): String = lock.withLock { "memory: ${map.size} answers, ${bytes / 1024} KB" }
}

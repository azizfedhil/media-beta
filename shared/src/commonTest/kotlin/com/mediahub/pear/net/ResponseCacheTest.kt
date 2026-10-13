package com.mediahub.pear.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResponseCacheTest {
    @Test
    fun memoryCacheKeepsAnswersAndDropsTheLeastRecentlyUsedWhenFull() = runTest {
        val cache = MemoryResponseCache(maxBytes = 25)
        cache.put("a", ByteArray(10), 1)
        cache.put("b", ByteArray(10), 2)
        assertNotNull(cache.get("a")) // "a" is now more recent than "b"
        cache.put("c", ByteArray(10), 3) // over the limit: "b" goes
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertEquals(1L, cache.get("a")!!.storedAtMillis)
        cache.put("a", byteArrayOf(1, 2), 9) // replacing keeps one entry per key
        assertContentEquals(byteArrayOf(1, 2), cache.get("a")!!.body)
        cache.clear()
        assertNull(cache.get("a"))
    }
}

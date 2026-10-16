package com.mediahub.pear.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileResponseCacheTest {
    private fun tempDir(): File = Files.createTempDirectory("pear-cache-test").toFile().also { it.deleteOnExit() }

    @Test
    fun fnvMatchesTheReferenceValues() {
        assertEquals("cbf29ce484222325", FileResponseCache.fnv1a64(""))
        assertEquals("af63dc4c8601ec8c", FileResponseCache.fnv1a64("a"))
    }

    @Test
    fun anAnswerSurvivesANewCacheOnTheSameFolder() = runTest {
        val dir = tempDir()
        FileResponseCache(dir).put("tmdb:/x?page=1", "héllo".encodeToByteArray(), 1234L)
        val hit = FileResponseCache(dir).get("tmdb:/x?page=1")
        assertNotNull(hit)
        assertEquals("héllo", hit.body.decodeToString())
        assertEquals(1234L, hit.storedAtMillis)
        assertNull(FileResponseCache(dir).get("tmdb:/other"))
        assertTrue(FileResponseCache(dir).describe().contains("1 answers"))
    }

    @Test
    fun aFileThatBelongsToAnotherKeyOrIsDamagedIsAMiss() {
        val bytes = FileResponseCache.encode("key-a", byteArrayOf(1, 2, 3), 5L)
        assertContentEquals(byteArrayOf(1, 2, 3), FileResponseCache.parse(bytes, "key-a")!!.body)
        assertNull(FileResponseCache.parse(bytes, "key-b")) // a hash collision can't return the wrong answer
        assertNull(FileResponseCache.parse(bytes.copyOf(3), "key-a"))
        assertNull(FileResponseCache.parse(ByteArray(0), "key-a"))
        assertNull(FileResponseCache.parse(byteArrayOf(127, 127, 127, 127, 0, 0), "key-a")) // absurd key length
    }

    @Test
    fun theFolderStaysUnderTheLimitAndKeepsTheNewestAnswers() = runTest {
        val dir = tempDir()
        val cache = FileResponseCache(dir, maxBytes = 2_600, trimEvery = 1)
        for (i in 1..6) {
            cache.put("k$i", ByteArray(1000), i.toLong())
            withContext(Dispatchers.Default) { delay(30) } // file times must differ
        }
        val size = dir.listFiles { f -> f.name.endsWith(".bin") }!!.sumOf { it.length() }
        assertTrue(size <= 2_600, "folder holds $size bytes")
        assertNotNull(cache.get("k6"))
        assertNull(cache.get("k1"))
        cache.clear()
        assertNull(cache.get("k6"))
    }

    @Test
    fun factFilesAreWrittenAndReadBack() {
        val dir = tempDir()
        val store = FileFactStore(File(dir, "facts"))
        assertNull(store.load("a.json"))
        store.save("a.json", """{"x":"é"}""")
        assertEquals("""{"x":"é"}""", FileFactStore(File(dir, "facts")).load("a.json"))
        store.save("a.json", "{}")
        assertEquals("{}", store.load("a.json"))
    }
}

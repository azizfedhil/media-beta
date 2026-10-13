package com.mediahub.pear.downloads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.DownloadRecord
import com.mediahub.pear.model.DownloadState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeps the bytes in memory, so a test can read what a download wrote. */
private class MemoryFiles : DownloadFiles {
    val data = HashMap<String, ByteArray>()

    override fun size(name: String): Long = (data[name]?.size ?: 0).toLong()

    override fun append(name: String, buffer: ByteArray, count: Int) {
        data[name] = (data[name] ?: ByteArray(0)) + buffer.copyOf(count)
    }

    override fun delete(name: String) {
        data.remove(name)
    }
}

/** Serves [body] from the offset it is asked for, and remembers each offset. Fails with [fail] when it is set. */
private class FakeTransport(private val body: ByteArray) : DownloadTransport {
    val offsets = mutableListOf<Long>()
    var fail: String? = null

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        onTotal: (Long) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        offsets += offset
        fail?.let { throw IllegalStateException(it) }
        onTotal(body.size.toLong())
        var pos = offset.toInt()
        while (pos < body.size) {
            val n = minOf(4, body.size - pos)
            onChunk(body.copyOfRange(pos, pos + n), n)
            pos += n
        }
    }
}

private fun film(): DownloadRecord = DownloadRecord(
    id = "tt1",
    imdb = "tt1",
    title = "Film",
    url = "https://example.test/film.mp4",
    fileName = "tt1.mp4",
    expectedBytes = 16,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {
    private val body = "0123456789abcdef".encodeToByteArray()

    @Test
    fun aDownloadRunsToTheEnd() {
        val scope = TestScope()
        val files = MemoryFiles()
        val manager = DownloadManager(PrefStore(), files, FakeTransport(body), scope)

        manager.enqueue(film())
        scope.advanceUntilIdle()

        val r = manager.record("tt1")!!
        assertEquals(DownloadState.Done, r.state)
        assertEquals(16L, r.bytes)
        assertEquals("0123456789abcdef", files.data["tt1.mp4"]!!.decodeToString())
    }

    @Test
    fun aPausedDownloadResumesFromTheBytesOnDisk() {
        val scope = TestScope()
        val prefs = PrefStore()
        val saved = listOf(film().copy(state = DownloadState.Paused, bytes = 4))
        prefs.setJsonText(PrefKeys.DOWNLOADS, PearJson.encodeToString(ListSerializer(DownloadRecord.serializer()), saved))
        val files = MemoryFiles().apply { data["tt1.mp4"] = "0123".encodeToByteArray() }
        val transport = FakeTransport(body)
        val manager = DownloadManager(prefs, files, transport, scope)

        // Nothing runs across a restart, so the list shows it paused until the user resumes it.
        assertEquals(DownloadState.Paused, manager.record("tt1")!!.state)

        manager.resume("tt1")
        scope.advanceUntilIdle()

        assertEquals(listOf(4L), transport.offsets)
        assertEquals("0123456789abcdef", files.data["tt1.mp4"]!!.decodeToString())
        assertEquals(DownloadState.Done, manager.record("tt1")!!.state)
    }

    @Test
    fun aFailureIsListedAndRetryDownloadsAgain() {
        val scope = TestScope()
        val transport = FakeTransport(body).apply { fail = "offline" }
        val manager = DownloadManager(PrefStore(), MemoryFiles(), transport, scope)

        manager.enqueue(film())
        scope.advanceUntilIdle()
        assertEquals(DownloadState.Failed, manager.record("tt1")!!.state)
        assertEquals("offline", manager.record("tt1")!!.error)

        transport.fail = null
        manager.retry("tt1")
        scope.advanceUntilIdle()
        assertEquals(DownloadState.Done, manager.record("tt1")!!.state)
    }

    @Test
    fun deleteRemovesTheFileAndTheRecord() {
        val scope = TestScope()
        val files = MemoryFiles()
        val manager = DownloadManager(PrefStore(), files, FakeTransport(body), scope)
        manager.enqueue(film())
        scope.advanceUntilIdle()

        manager.delete("tt1")
        scope.advanceUntilIdle()

        assertNull(manager.record("tt1"))
        assertNull(files.data["tt1.mp4"])
    }

    @Test
    fun aSecondDownloadOfTheSameTitleIsIgnored() {
        val scope = TestScope()
        val manager = DownloadManager(PrefStore(), MemoryFiles(), FakeTransport(body), scope)

        manager.enqueue(film())
        manager.enqueue(film())
        scope.advanceUntilIdle()

        assertEquals(1, manager.records.value.size)
    }

    @Test
    fun theListIsSavedInPreferences() {
        val scope = TestScope()
        val prefs = PrefStore()
        val manager = DownloadManager(prefs, MemoryFiles(), FakeTransport(body), scope)

        manager.enqueue(film())
        scope.advanceUntilIdle()

        assertTrue(prefs.jsonText(PrefKeys.DOWNLOADS)!!.contains("\"tt1\""))
    }
}

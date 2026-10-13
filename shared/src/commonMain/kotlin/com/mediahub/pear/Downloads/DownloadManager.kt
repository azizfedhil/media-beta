package com.mediahub.pear.downloads

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.DownloadRecord
import com.mediahub.pear.model.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** Where downloaded files are kept: one file per download, appended to as its bytes arrive. */
interface DownloadFiles {
    /** Bytes already on disk for [name]; 0 when there is no file. */
    fun size(name: String): Long

    /** Appends the first [count] bytes of [buffer] to [name], creating the file when needed. */
    fun append(name: String, buffer: ByteArray, count: Int)

    fun delete(name: String)
}

/** Fetches a link into a file's bytes. */
interface DownloadTransport {
    /**
     * Fetches [url] from byte [offset] on, with [headers]. Calls [onTotal] with the whole file's size once the answer is known
     * (-1 when the server does not say), then [onChunk] for each piece after [offset], in order. Throws on any failure.
     * Cancelling the calling coroutine stops the transfer.
     */
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        onTotal: (Long) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    )
}

/**
 * The downloads: a queue of files, fetched one at a time into [files] and listed in the index (the `downloads.records`
 * preference), so the list survives a restart. Every change to the list runs on [scope]. Give it a single-threaded scope and
 * the records change in one order. The UI reads [records] and calls the actions from anywhere.
 */
class DownloadManager(
    private val prefs: PrefStore,
    private val files: DownloadFiles,
    private val transport: DownloadTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = { 0L },
) {
    private val list = MutableStateFlow(load())
    val records: StateFlow<List<DownloadRecord>> = list.asStateFlow()

    /** The running transfers, and when each last reported progress (both used on [scope] only). */
    private val running = HashMap<String, Job>()
    private val lastProgress = HashMap<String, Long>()

    /** The download listed under [id] (see `downloadKey`), if any. */
    fun record(id: String): DownloadRecord? = list.value.firstOrNull { it.id == id }

    /** Queues [record] and starts it. Nothing happens when a download with the same id is already listed. */
    fun enqueue(record: DownloadRecord) {
        scope.launch {
            if (list.value.any { it.id == record.id }) return@launch
            // A file left under this name belongs to an older download, possibly from another source: start it afresh.
            files.delete(record.fileName)
            put(list.value + record.copy(state = DownloadState.Queued, bytes = 0, error = null, added = now()))
            start(record.id)
        }
    }

    fun pause(id: String) {
        scope.launch {
            running.remove(id)?.cancel()
            change(id) {
                if (it.state == DownloadState.Queued || it.state == DownloadState.Downloading) it.copy(state = DownloadState.Paused) else it
            }
        }
    }

    /** Continues a paused or failed download from the bytes already on disk. */
    fun resume(id: String) {
        scope.launch {
            val r = record(id) ?: return@launch
            if (r.state == DownloadState.Paused || r.state == DownloadState.Failed) {
                change(id) { it.copy(state = DownloadState.Queued, error = null) }
                start(id)
            }
        }
    }

    fun retry(id: String) = resume(id)

    /** Stops the download and removes it: the file is deleted too. */
    fun delete(id: String) {
        scope.launch {
            running.remove(id)?.cancel()
            val r = record(id) ?: return@launch
            files.delete(r.fileName)
            lastProgress.remove(id)
            put(list.value.filterNot { it.id == id })
        }
    }

    /** Bytes of every listed download, finished or not. */
    fun usedBytes(): Long = list.value.sumOf { it.bytes }

    private fun start(id: String) {
        if (running[id]?.isActive == true) return
        running[id] = scope.launch { run(id) }
    }

    /** One transfer, on [scope]. A cancelled one (paused or deleted) leaves its state to the caller. */
    private suspend fun run(id: String) {
        val me = currentCoroutineContext()[Job]
        val target = record(id) ?: return
        change(id) { it.copy(state = DownloadState.Downloading, error = null) }
        var received = files.size(target.fileName)
        try {
            transport.fetch(
                url = target.url,
                headers = target.headers,
                offset = received,
                onTotal = { total -> scope.launch { if (total > 0) change(id, save = false) { it.copy(expectedBytes = total) } } },
                onChunk = { buffer, count ->
                    files.append(target.fileName, buffer, count)
                    received += count
                    val bytes = received
                    scope.launch { progress(id, bytes) }
                },
            )
            change(id) {
                it.copy(
                    state = DownloadState.Done,
                    bytes = received,
                    expectedBytes = if (it.expectedBytes > 0) it.expectedBytes else received,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            change(id) { it.copy(state = DownloadState.Failed, error = e.message ?: "The download failed") }
        } finally {
            if (running[id] === me) running.remove(id)
        }
    }

    /**
     * Progress comes in every 64 KB, and a piece's update is queued before the finish is written, so it can run after the
     * transfer is already Done. Progress therefore counts only while the download is still running: a late piece never
     * overwrites the final size. The list is updated at most twice a second, which is all the screen needs.
     */
    private fun progress(id: String, bytes: Long) {
        val at = now()
        if (at - (lastProgress[id] ?: Long.MIN_VALUE / 2) < 500) return
        lastProgress[id] = at
        change(id, save = false) { if (it.state == DownloadState.Downloading) it.copy(bytes = bytes) else it }
    }

    /** Must be called on [scope]. */
    private fun change(id: String, save: Boolean = true, transform: (DownloadRecord) -> DownloadRecord) {
        val current = list.value
        val i = current.indexOfFirst { it.id == id }
        if (i < 0) return
        put(current.toMutableList().also { it[i] = transform(current[i]) }, save)
    }

    private fun put(next: List<DownloadRecord>, save: Boolean = true) {
        list.value = next
        if (save) prefs.setJsonText(PrefKeys.DOWNLOADS, PearJson.encodeToString(ListSerializer(DownloadRecord.serializer()), next))
    }

    private fun load(): List<DownloadRecord> {
        val json = prefs.jsonText(PrefKeys.DOWNLOADS) ?: return emptyList()
        val saved = runCatching { PearJson.decodeFromString(ListSerializer(DownloadRecord.serializer()), json) }
            .getOrDefault(emptyList())
        // Nothing runs across a restart: anything that was in flight is paused, and resumes from the bytes on disk.
        return saved.map {
            if (it.state == DownloadState.Queued || it.state == DownloadState.Downloading) it.copy(state = DownloadState.Paused) else it
        }
    }
}

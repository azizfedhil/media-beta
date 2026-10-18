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
import kotlinx.coroutines.delay
import com.mediahub.pear.model.blocksReplacement
import com.mediahub.pear.model.StreamSize

/** Where downloaded files are kept: one file per download, appended to as its bytes arrive. */
interface DownloadFiles {
    /** Bytes already on disk for [name]; 0 when there is no file. */
    fun size(name: String): Long

    /** Appends the first [count] bytes of [buffer] to [name], creating the file when needed. */
    fun append(name: String, buffer: ByteArray, count: Int)

    fun delete(name: String)

    /** Closes [name] if it is held open between pieces. Called when a transfer stops, finishes or fails. */
    fun close(name: String) {}

    /** Where [name] is on disk, when it is there: what the player opens to play a finished download. */
    fun path(name: String): String? = null
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

/** Attempts after the first for a transient failure, and the first wait (it doubles each time). */
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MILLIS = 2_000L

/** Content types that mean the link is a page or a playlist, not a video file. */
private val NOT_A_VIDEO_TYPES = setOf(
    "text/html", "application/xhtml+xml", "application/dash+xml",
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
)

/**
 * A transfer failure in words a person can read. A [transient] one (a server error, a busy source, a dropped connection)
 * is retried; the rest are listed as failed at once.
 */
class DownloadFailure(message: String, val transient: Boolean = false) : Exception(message)

/** The message for an HTTP status that stops a download, worded for the person who has to act on it. */
fun friendlyHttpError(code: Int): String = when (code) {
    401, 403 -> "The source refused the file (HTTP $code). Try another source."
    404, 410 -> "The file is no longer at that link (HTTP $code). Try another source."
    429 -> "The source is busy (HTTP 429). Try again in a few minutes."
    in 500..599 -> "The source had a server error (HTTP $code)."
    else -> "The server answered $code."
}

/** Why a response is not a video, when its content type says so; null when it may be one. */
fun notAVideoMessage(contentType: String?): String? {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return if (type in NOT_A_VIDEO_TYPES) "The link gives a web page or a playlist, not a video file. Try another source." else null
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

    /** Bytes per second of each running download, from its last few progress reports. Memory only, never saved. */
    private val speed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val speeds: StateFlow<Map<String, Long>> = speed.asStateFlow()

    /** When and how many bytes each running download last reported, for [measureSpeed]. */
    private val speedMarks = HashMap<String, SpeedMark>()

    private class SpeedMark(val at: Long, val bytes: Long)

    /** The download listed under [id] (see `downloadKey`), if any. */
    fun record(id: String): DownloadRecord? = list.value.firstOrNull { it.id == id }

    /** The file of [r] on disk, or null when it is not there (a finished download's file can be removed by hand). */
    fun localPath(r: DownloadRecord): String? = files.path(r.fileName)

    /**
     * Queues [record] and starts it. The same link that is already listed is not added twice. Another source of the same
     * title replaces a download that has stopped (failed or paused); it waits behind one that is queued, running or done.
     */
    fun enqueue(record: DownloadRecord) {
        scope.launch {
            val existing = list.value.firstOrNull { it.id == record.id }
            if (existing != null) {
                if (existing.url == record.url || existing.state.blocksReplacement) return@launch
                running.remove(record.id)?.cancel()
                lastProgress.remove(record.id)
                files.delete(existing.fileName)
                put(list.value.filterNot { it.id == record.id })
            }
            // A file left under this name belongs to an older download: start it afresh.
            files.delete(record.fileName)
            put(list.value + record.copy(state = DownloadState.Queued, bytes = 0, error = null, added = now()))
            start(record.id)
        }
    }

    fun pause(id: String) {
        scope.launch {
            running.remove(id)?.cancel()
            clearSpeed(id)
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
            clearSpeed(id)
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

    /**
     * One transfer, on [scope]. A transient failure is retried from the bytes already on disk, after a wait that doubles each
     * time. A cancelled transfer (paused or deleted) leaves its state to the caller.
     */
    private suspend fun run(id: String) {
        val me = currentCoroutineContext()[Job]
        val target = record(id) ?: return
        change(id) { it.copy(state = DownloadState.Downloading, error = null) }
        // A new run is a new baseline: the speed is measured again from its first reports.
        clearSpeed(id)
        var received = 0L
        var attempt = 0
        try {
            while (true) {
                received = files.size(target.fileName)
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
                    break
                } catch (e: DownloadFailure) {
                    if (!e.transient || attempt >= MAX_RETRIES) throw e
                    attempt++
                    delay(RETRY_DELAY_MILLIS shl (attempt - 1))
                }
            }
            // A 200 with a body far smaller than the source said is an error page or a stub, not the video.
            if (target.expectedBytes > 0 && received < target.expectedBytes / 4) {
                throw DownloadFailure(
                    "The file is far smaller than the source said (${StreamSize.format(received)}, about ${StreamSize.format(target.expectedBytes)}). Try another source.",
                )
            }
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
            // A download stopped by a pause or a delete ends as that, never as a failure, whatever the transfer threw on the way out.
            if (me?.isActive == false) throw CancellationException("The download was stopped.")
            change(id) { it.copy(state = DownloadState.Failed, error = e.message ?: "The download failed") }
        } finally {
            files.close(target.fileName)
            clearSpeed(id)
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
        measureSpeed(id, bytes, at)
    }

    /**
     * The speed of a running download from its last report: the bytes since then over the time since then, smoothed so the
     * figure does not jump. Only a download that is downloading is measured. Must be called on [scope].
     */
    private fun measureSpeed(id: String, bytes: Long, at: Long) {
        if (record(id)?.state != DownloadState.Downloading) return
        // The first report only sets the mark: there is no earlier point to measure from.
        val mark = speedMarks.put(id, SpeedMark(at, bytes)) ?: return
        val dt = at - mark.at
        if (dt <= 0) return
        val instant = maxOf(0L, bytes - mark.bytes) * 1_000 / dt
        val old = speed.value[id] ?: 0L
        val smoothed = if (old > 0) (old * 0.6 + instant * 0.4).toLong() else instant
        speed.value = speed.value + (id to smoothed)
    }

    /** Forgets the speed of [id]: a download that is paused, deleted or starting again is measured afresh. */
    private fun clearSpeed(id: String) {
        speedMarks.remove(id)
        if (speed.value.containsKey(id)) speed.value = speed.value - id
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

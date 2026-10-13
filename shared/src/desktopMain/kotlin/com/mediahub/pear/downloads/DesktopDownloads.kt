package com.mediahub.pear.downloads

import com.mediahub.pear.data.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

actual fun platformDownloadFiles(): DownloadFiles = FolderDownloadFiles(File(AppDirs.detect().config, "downloads"))

actual fun platformDownloadTransport(): DownloadTransport = HttpDownloadTransport()

/**
 * The user agent a download sends. Several file hosts refuse unknown agents, so it is an ordinary browser's. An add-on that
 * asks for its own `User-Agent` in the request headers still gets it, because the headers are set after this one.
 */
const val DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** Downloads as files in [dir], which is created when the first bytes arrive. */
class FolderDownloadFiles(private val dir: File) : DownloadFiles {
    /** One stream per file while its transfer runs: opened at the first piece, closed when the transfer stops. */
    private val open = HashMap<String, FileOutputStream>()

    override fun size(name: String): Long = File(dir, name).takeIf { it.isFile }?.length() ?: 0L

    override fun append(name: String, buffer: ByteArray, count: Int) {
        synchronized(open) {
            val stream = open[name] ?: run {
                dir.mkdirs()
                FileOutputStream(File(dir, name), true).also { open[name] = it }
            }
            stream.write(buffer, 0, count)
        }
    }

    override fun close(name: String) {
        synchronized(open) { open.remove(name)?.close() }
    }

    override fun delete(name: String) {
        close(name)
        File(dir, name).delete()
    }
}

/** The statuses that send a download to another address. */
private val REDIRECTS = setOf(301, 302, 303, 307, 308)

/** How many redirects one download may follow before it is given up. */
private const val MAX_REDIRECTS = 5

/**
 * Streams a link with the JDK's HTTP client, so nothing is held in memory whole. A range request resumes a partial file.
 * Redirects are followed by hand, because HttpURLConnection will not follow one from http to https. Cancelling the download
 * is checked between reads (a read blocks for at most the read timeout).
 */
class HttpDownloadTransport(private val userAgent: String = DOWNLOAD_USER_AGENT) : DownloadTransport {
    @Suppress("DEPRECATION")
    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        onTotal: (Long) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val connection = open(url, headers, offset)
                try {
                    val code = connection.responseCode
                    if (code == 416 && offset > 0) {
                        // The range starts past the end: the file is already whole.
                        onTotal(offset)
                        return@withContext
                    }
                    if (code !in 200..299) {
                        // A server error or a busy source may pass in a moment; a refusal or a missing file will not.
                        throw DownloadFailure(friendlyHttpError(code), transient = code >= 500 || code == 429)
                    }
                    // A web page or a playlist behind a "video" link would be saved as the file: refuse it before any byte is written.
                    notAVideoMessage(connection.contentType)?.let { throw DownloadFailure(it) }

                    val ranged = code == 206
                    val total = if (ranged) totalFromRange(connection.getHeaderField("Content-Range")) else connection.contentLengthLong.takeIf { it >= 0 }
                    onTotal(total ?: -1L)

                    // A server that ignores the range sends the whole file again: skip the part already on disk.
                    var skip = if (ranged) 0L else offset
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            var start = 0
                            if (skip > 0) {
                                start = minOf(skip, read.toLong()).toInt()
                                skip -= start
                            }
                            val count = read - start
                            if (count > 0) {
                                // A cancelled download (paused or deleted) must not write this piece: its file may be gone already.
                                currentCoroutineContext().ensureActive()
                                if (start > 0) buffer.copyInto(buffer, 0, start, read)
                                onChunk(buffer, count)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                // A dropped or timed-out connection is worth another try from the bytes already on disk.
                throw DownloadFailure("The connection was lost (${e.message ?: "no detail"}).", transient = true)
            }
        }
    }

    /**
     * Opens [start] with the download's headers and a range from [offset], following redirects. Each hop carries the same
     * headers and range.
     */
    @Suppress("DEPRECATION")
    private fun open(start: String, headers: Map<String, String>, offset: Long): HttpURLConnection {
        var current = start
        var hops = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val code = connection.responseCode
            if (code !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw DownloadFailure("The source redirected without saying where (HTTP $code).")
            if (++hops > MAX_REDIRECTS) throw DownloadFailure("The source redirected too many times.")
            current = URL(URL(current), location).toString()
        }
    }

    /** The whole file's size from `Content-Range: bytes 100-199/1000` (null for `*`). */
    private fun totalFromRange(value: String?): Long? = value?.substringAfter('/', "")?.toLongOrNull()
}

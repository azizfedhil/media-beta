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

/** Downloads as files in [dir], which is created when the first bytes arrive. */
class FolderDownloadFiles(private val dir: File) : DownloadFiles {
    override fun size(name: String): Long = File(dir, name).takeIf { it.isFile }?.length() ?: 0L

    override fun append(name: String, buffer: ByteArray, count: Int) {
        dir.mkdirs()
        FileOutputStream(File(dir, name), true).use { it.write(buffer, 0, count) }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/**
 * Streams a link with the JDK's HTTP client, so nothing is held in memory whole. A range request resumes a partial file.
 * Cancelling the download is checked between reads (a read blocks for at most the read timeout).
 */
class HttpDownloadTransport(private val userAgent: String = "Pear/1.0") : DownloadTransport {
    // URL(String) is deprecated, but it accepts the odd links add-ons hand out, which URI would reject.
    @Suppress("DEPRECATION")
    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        onTotal: (Long) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

                val code = connection.responseCode
                if (code == 416 && offset > 0) {
                    // The range starts past the end: the file is already whole.
                    onTotal(offset)
                    return@withContext
                }
                if (code !in 200..299) throw IOException("The server answered $code")

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
        }
    }

    /** The whole file's size from `Content-Range: bytes 100-199/1000` (null for `*`). */
    private fun totalFromRange(value: String?): Long? = value?.substringAfter('/', "")?.toLongOrNull()
}

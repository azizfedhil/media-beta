package com.mediahub.pear.net

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** SHA-256 of [file] as lowercase hex. Reads in chunks, so a 100 MB file does not have to fit in memory. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** True for exactly 64 hex digits, in either case. Anything else (a `TBD` placeholder, say) is not a pin. */
fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * Streams [url] into the file [into], reporting progress as [onProgress] (bytes done, total or -1 if unknown).
 * Throws [IOException] for anything that is not the file: an error status, or a web page (Google Drive answers a
 * `/view` link with HTML). Redirects are followed. On failure [into] may hold a partial file; the caller removes it.
 */
fun downloadTo(
    url: String,
    into: File,
    timeoutMillis: Int = 30_000,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
) {
    val connection = try {
        URI(url).toURL().openConnection() as HttpURLConnection
    } catch (e: Exception) {
        throw IOException("not a usable address", e)
    }
    try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("User-Agent", "Pear-desktop/1.0")
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("the server answered $status")
        if (connection.contentType.orEmpty().lowercase().startsWith("text/html")) {
            throw IOException("the server sent a web page instead of the file")
        }
        val total = connection.contentLengthLong
        into.parentFile?.mkdirs()
        var done = 0L
        connection.inputStream.use { input ->
            into.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                onProgress(0L, total)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}

/** The outcome of [fetchVerified]. */
sealed interface FetchResult {
    /** The target file is there and has the pinned hash. */
    data object Ready : FetchResult

    /** Nothing usable was fetched. [reasons] has one line per source (host and cause), for the user to read. */
    class Failed(val reasons: List<String>) : FetchResult
}

/**
 * Makes sure [target] is the file whose SHA-256 is [sha256], downloading it from the first of [urls] that provides it.
 *
 * A download is checked before it touches [target]: it goes to a staging file next to the target, and only a file with
 * the right hash is moved into place. A wrong, partial or web-page answer is deleted, and the next source is tried.
 * An existing [target] with the right hash is left alone, and nothing is downloaded.
 */
fun fetchVerified(
    urls: List<String>,
    sha256: String,
    target: File,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): FetchResult {
    if (!isSha256Hex(sha256)) {
        return FetchResult.Failed(listOf("this build has no SHA-256 pin, so it does not download this file"))
    }
    if (target.isFile && sha256Hex(target).equals(sha256, ignoreCase = true)) return FetchResult.Ready
    if (urls.isEmpty()) return FetchResult.Failed(listOf("this build has no download address"))

    val reasons = mutableListOf<String>()
    val staging = File(target.parentFile, target.name + ".download")
    for (url in urls) {
        val host = hostOf(url)
        try {
            downloadTo(url, staging, onProgress = onProgress)
            val actual = sha256Hex(staging)
            if (!actual.equals(sha256, ignoreCase = true)) {
                reasons += "$host: the file's SHA-256 is $actual, not the pinned value"
                continue
            }
            target.parentFile?.mkdirs()
            moveReplacing(staging, target)
            return FetchResult.Ready
        } catch (e: Exception) {
            reasons += "$host: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            staging.delete()
        }
    }
    return FetchResult.Failed(reasons)
}

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "(bad address)"

private fun moveReplacing(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

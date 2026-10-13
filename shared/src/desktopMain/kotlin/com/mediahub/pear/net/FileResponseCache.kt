package com.mediahub.pear.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers on disk, one file each, in the cache folder (safe to delete). The Swift clients' `URLCache` allows 50 MB;
 * so does this. The oldest-used files go first.
 *
 * File layout: key length (int), key (UTF-8), stored-at (long), body. The key is checked on read, so two keys that
 * happen to share a file name can never return each other's answer. Writes go through a temp file and a rename.
 */
class FileResponseCache(
    private val dir: File,
    private val maxBytes: Long = 50L shl 20,
    private val trimEvery: Int = 40,
) : ResponseCache {
    private val trimLock = Mutex()
    private val puts = AtomicInteger()

    override suspend fun get(key: String): CachedBody? = withContext(Dispatchers.Default) {
        try {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            val parsed = parse(file.readBytes(), key) ?: return@withContext null
            file.setLastModified(System.currentTimeMillis()) // "used", for the oldest-first trim
            parsed
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun put(key: String, body: ByteArray, nowMillis: Long) {
        withContext(Dispatchers.Default) {
            try {
                dir.mkdirs()
                val target = fileFor(key)
                val tmp = File.createTempFile("pear", ".tmp", dir)
                try {
                    tmp.writeBytes(encode(key, body, nowMillis))
                    try {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: IOException) {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    tmp.delete() // already gone after a successful move
                }
                if (puts.incrementAndGet() % trimEvery == 0) trim()
            } catch (_: IOException) {
                // A cache that can't write is just a miss next time.
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) { entries().forEach { it.delete() } }
    }

    override suspend fun describe(): String = withContext(Dispatchers.Default) {
        val files = entries()
        "disk: ${files.size} answers, ${files.sumOf { it.length() } / 1024} KB in ${dir.path}"
    }

    /** Deletes the least recently used files until the folder is under 80% of the limit. */
    internal fun trim() {
        if (!trimLock.tryLock()) return
        try {
            val files = entries()
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            val target = maxBytes / 5 * 4
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        } finally {
            trimLock.unlock()
        }
    }

    private fun entries(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList() ?: emptyList()

    private fun fileFor(key: String): File = File(dir, fnv1a64(key) + ".bin")

    internal companion object {
        fun fnv1a64(text: String): String {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in text.encodeToByteArray()) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h).padStart(16, '0')
        }

        fun encode(key: String, body: ByteArray, storedAt: Long): ByteArray {
            val out = ByteArrayOutputStream(body.size + key.length + 16)
            DataOutputStream(out).use {
                val k = key.encodeToByteArray()
                it.writeInt(k.size)
                it.write(k)
                it.writeLong(storedAt)
                it.write(body)
            }
            return out.toByteArray()
        }

        /** Null for a damaged file or one that belongs to another key. */
        fun parse(bytes: ByteArray, expectedKey: String): CachedBody? {
            return try {
                val input = DataInputStream(ByteArrayInputStream(bytes))
                val keyLength = input.readInt()
                if (keyLength < 0 || keyLength > bytes.size) return null
                val key = ByteArray(keyLength).also { input.readFully(it) }.decodeToString()
                if (key != expectedKey) return null
                val storedAt = input.readLong()
                CachedBody(input.readBytes(), storedAt)
            } catch (_: IOException) {
                null
            }
        }
    }
}

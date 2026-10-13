package com.mediahub.pear.data

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Settings in one JSON file (see [PrefsCodec]). Saves are debounced: a burst of changes becomes one write a
 * moment later, on a background thread, through a temporary file and a rename so a crash can't leave half a file.
 * A file that can't be parsed is kept as `settings.json.bad` instead of being overwritten.
 */
class FilePrefsPersistence(
    private val file: File,
    private val debounceMillis: Long = 400,
    registerShutdownHook: Boolean = true,
) : PrefsPersistence {
    private val pending = AtomicReference<Map<String, PrefValue>?>(null)
    private val lock = Any()
    private var scheduled = false
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pear-settings-writer").apply { isDaemon = true } }
    }

    init {
        if (registerShutdownHook) Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "pear-settings-flush"))
    }

    override fun load(): Map<String, PrefValue> {
        if (!file.isFile) return emptyMap()
        val text = try { file.readText(StandardCharsets.UTF_8) } catch (e: IOException) {
            System.err.println("Pear: could not read ${file.path}: ${e.message}")
            return emptyMap()
        }
        PrefsCodec.decode(text)?.let { return it }
        val bad = File(file.path + ".bad")
        runCatching { Files.move(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        System.err.println("Pear: ${file.path} was not a settings file; kept as ${bad.path}")
        return emptyMap()
    }

    override fun save(values: Map<String, PrefValue>) {
        pending.set(values)
        synchronized(lock) {
            if (scheduled) return
            scheduled = true
        }
        executor.schedule({ synchronized(lock) { scheduled = false }; writePending() }, debounceMillis, TimeUnit.MILLISECONDS)
    }

    override fun flush() = writePending()

    private fun writePending() {
        synchronized(file) {
            val values = pending.getAndSet(null) ?: return
            try {
                file.absoluteFile.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(PrefsCodec.encode(values), StandardCharsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                System.err.println("Pear: could not save settings to ${file.path}: ${e.message}")
                pending.compareAndSet(null, values) // keep it for the next save or flush
            }
        }
    }
}

package com.mediahub.pear.log

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The trace's file sink on the JVM (desktop; Android never creates one). [start] opens `trace-<time>.log` in the logs
 * folder and starts two daemon threads: a writer that drains a queue, and a tick that writes one STAT line a second.
 * Callers never touch the disk. Each line is queued, and the writer does the I/O.
 *
 * Line format: `HH:mm:ss.SSS T+123.456 CAT  message`, where T+ is seconds since [start].
 */
class PearTraceFile(
    private val appVersion: String,
    private val platform: String,
) : TraceSink {
    /** Extra header lines the desktop sets before [start]: screens, the render API, the JVM arguments. */
    var headerExtra: () -> List<String> = { emptyList() }

    private val lifecycle = Any()
    private val tickLock = Any()
    private val queue = LinkedBlockingQueue<String>()
    private val counters = ConcurrentHashMap<String, LongAdder>()
    private val samples = ConcurrentHashMap<String, SampleAcc>()
    private val recentStats = ArrayDeque<String>()
    private val markCount = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var full = false
    @Volatile private var startNanos = 0L
    @Volatile private var file: File? = null
    private var writtenBytes = 0L
    private var ticks = 0
    private var writer: Thread? = null
    private var ticker: Thread? = null

    /** Opens the file, writes the header, and starts the threads. False if the logs folder is not ready. */
    override fun start(): Boolean = synchronized(lifecycle) {
        if (running) return@synchronized true
        val dir = PearLogFile.logsDir() ?: return@synchronized false
        try {
            pruneOld(dir, "trace-", ".log", KEEP_TRACES - 1)
            val f = File(dir, "trace-${LocalDateTime.now().format(FILE_STAMP)}.log")
            file = f
            writtenBytes = if (f.exists()) f.length() else 0L
            full = false
            ticks = 0
            markCount.set(0)
            // Nothing left over from an earlier trace in this run goes into this one.
            queue.clear()
            counters.clear()
            samples.clear()
            synchronized(recentStats) { recentStats.clear() }
            startNanos = System.nanoTime()
            enqueue(line("HEAD", "Pear $appVersion on $platform; trace started ${ZonedDateTime.now().format(ZONED)}"))
            for (text in headerLines()) enqueue(line("HEAD", scrub(text)))
            running = true
            writer = startDaemon("pear-trace") { writerLoop() }
            ticker = startDaemon("pear-trace-tick") { tickLoop() }
            true
        } catch (_: Throwable) {
            running = false
            false
        }
    }

    /** Writes the last STAT line and a STOP line, waits for the writer to drain, and closes the file. */
    override fun stop() {
        val w = synchronized(lifecycle) {
            if (!running) return
            ticker?.interrupt()
            tickOnce(stopping = true)
            enqueue(line("STOP", "trace stopped"))
            running = false
            writer
        }
        if (w != null && w !== Thread.currentThread()) runCatching { w.join(WRITER_JOIN_MS) }
        synchronized(lifecycle) {
            writer = null
            ticker = null
            file = null
        }
    }

    override fun event(cat: String, msg: String) {
        if (!running || full) return
        enqueue(line(cat, msg))
    }

    override fun count(key: String, n: Long) {
        if (!running || full) return
        val adder = counters[key] ?: counters.computeIfAbsent(key) { LongAdder() }
        adder.add(n)
    }

    override fun sample(key: String, value: Double) {
        if (!running || full) return
        val acc = samples[key] ?: samples.computeIfAbsent(key) { SampleAcc() }
        acc.add(value)
    }

    /** The MARK line, the facts, and the last three STAT lines again, so the moment is on the page the user looks at. */
    override fun mark() {
        if (!running || full) return
        val n = markCount.incrementAndGet()
        enqueue(line("MARK", "#$n  (last 3 STAT lines follow)"))
        enqueue(factsLine())
        val recent = synchronized(recentStats) { recentStats.takeLast(3) }
        for (stat in recent) enqueue(stat)
    }

    /** Never blocks: when the writer falls far behind, the oldest line goes. */
    private fun enqueue(text: String) {
        if (queue.size >= QUEUE_LIMIT) queue.poll()
        queue.offer(text)
    }

    private fun writerLoop() {
        val batch = ArrayList<String>(256)
        while (running || queue.isNotEmpty()) {
            val first = queue.poll(200L, TimeUnit.MILLISECONDS) ?: continue
            batch.clear()
            batch.add(first)
            queue.drainTo(batch, MAX_BATCH - 1)
            if (!writeBatch(batch)) return
        }
    }

    /** Appends one batch. False when the file is full: the writer then stops, and the trace stops itself. */
    private fun writeBatch(lines: List<String>): Boolean {
        val f = file ?: return false
        val bytes = buildString { for (l in lines) append(l).append('\n') }.toByteArray(Charsets.UTF_8)
        if (writtenBytes + bytes.size > MAX_BYTES) {
            full = true
            runCatching { f.appendBytes("TRACE FULL, STOPPED\n".toByteArray(Charsets.UTF_8)) }
            // Stopped from another thread, because stop() waits for this writer to finish.
            startDaemon("pear-trace-full") { PearTrace.stop() }
            return false
        }
        runCatching { f.appendBytes(bytes) }
        writtenBytes += bytes.size
        return true
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(1_000L)
            } catch (_: InterruptedException) {
                return
            }
            if (running) tickOnce(stopping = false)
        }
    }

    /** One STAT line for the second just gone: every count and sample, then they are reset. */
    private fun tickOnce(stopping: Boolean) {
        synchronized(tickLock) {
            ticks++
            val body = StringBuilder()
            for (key in counters.keys.sorted()) {
                val v = counters[key]?.sumThenReset() ?: 0L
                if (v == 0L) continue
                if (body.isNotEmpty()) body.append(' ')
                body.append(key).append('=').append(v)
            }
            if (body.isEmpty()) body.append('-')
            for (key in samples.keys.sorted()) {
                val s = samples[key]?.takeAndReset() ?: continue
                body.append(" | ").append(key).append(" n=").append(s.n)
                    .append(" min=").append(fmt(s.min))
                    .append(" avg=").append(fmt(s.sum / s.n))
                    .append(" max=").append(fmt(s.max))
            }
            val stat = line("STAT", body)
            synchronized(recentStats) {
                recentStats.addLast(stat)
                while (recentStats.size > RECENT_STATS) recentStats.removeFirst()
            }
            enqueue(stat)
            if (!stopping && ticks % FACTS_EVERY == 0) enqueue(factsLine())
        }
    }

    private fun factsLine(): String {
        val facts = PearLogFile.contextSnapshot().toSortedMap()
        val body = if (facts.isEmpty()) "(none)" else facts.entries.joinToString("  ") { "${it.key}=${it.value}" }
        return line("FACTS", body)
    }

    private fun line(cat: String, msg: CharSequence): String {
        val ms = (System.nanoTime() - startNanos) / 1_000_000L
        val frac = (ms % 1000L).toString().padStart(3, '0')
        return buildString(64 + msg.length) {
            append(LocalTime.now().format(CLOCK)).append(" T+").append(ms / 1000L).append('.').append(frac)
            append(' ').append(cat.padEnd(CAT_WIDTH)).append("  ").append(msg)
        }
    }

    private fun headerLines(): List<String> {
        val rt = Runtime.getRuntime()
        val out = ArrayList<String>()
        out.add("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} arch=${System.getProperty("os.arch")}")
        out.add(
            "java=${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")} " +
                "maxHeap=${rt.maxMemory() / MB} MB cpus=${rt.availableProcessors()}",
        )
        out.addAll(runCatching { headerExtra() }.getOrDefault(emptyList()))
        return out
    }

    /** Min, sum and max of the values one key got this second. */
    private class SampleAcc {
        private var n = 0L
        private var sum = 0.0
        private var min = Double.POSITIVE_INFINITY
        private var max = Double.NEGATIVE_INFINITY

        /** A short lock: the only other taker is the one-second tick. */
        fun add(v: Double) {
            synchronized(this) {
                n++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
        }

        fun takeAndReset(): Snapshot? = synchronized(this) {
            if (n == 0L) return@synchronized null
            val s = Snapshot(n, sum, min, max)
            n = 0L
            sum = 0.0
            min = Double.POSITIVE_INFINITY
            max = Double.NEGATIVE_INFINITY
            s
        }

        class Snapshot(val n: Long, val sum: Double, val min: Double, val max: Double)
    }

    companion object {
        private const val QUEUE_LIMIT = 5_000
        private const val MAX_BATCH = 1_000
        private const val MAX_BYTES = 12L * 1024L * 1024L
        private const val KEEP_TRACES = 5
        private const val KEEP_MPV_LOGS = 3
        private const val KEEP_DIAGNOSTICS = 3
        private const val RECENT_STATS = 10
        private const val FACTS_EVERY = 10
        private const val CAT_WIDTH = 6
        private const val WRITER_JOIN_MS = 3_000L
        private const val MB = 1024L * 1024L
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val ZONED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz XXX")

        /** A user's folder name: `C:\Users\<name>`, `/Users/<name>` or `/home/<name>`. */
        private val USER_PATH = Regex("""([A-Za-z]:[\\/]Users[\\/]|/Users/|/home/)[^\\/\s"',;]+""")

        /** Anything shaped like `scheme://...`. */
        private val URL_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s'"<>]+""")

        /** The file for a new mpv log, after the old ones are pruned to make room. Null if the folder is not ready. */
        fun newMpvLog(dir: File): File? = try {
            pruneOld(dir, "mpv-", ".log", KEEP_MPV_LOGS - 1)
            File(dir, "mpv-${LocalDateTime.now().format(FILE_STAMP)}.log")
        } catch (_: Throwable) {
            null
        }

        /**
         * Writes `Pear-diagnostics-<time>.zip` into the logs folder: the logs, the traces, mpv's logs (with stream addresses
         * cut down to host and path), the crash briefings, and `system-info.txt` with [extraText]. Keeps the three newest
         * zips. Returns the zip's absolute path, or null on failure.
         */
        fun exportZip(extraText: String): String? {
            return try {
                val dir = PearLogFile.logsDir() ?: return null
                val zip = File(dir, "Pear-diagnostics-${LocalDateTime.now().format(FILE_STAMP)}.zip")
                val names = dir.listFiles()?.filter { it.isFile && inExport(it.name) }?.map { it.name }?.sorted().orEmpty()
                ZipOutputStream(FileOutputStream(zip).buffered()).use { out ->
                    for (name in names) {
                        out.putNextEntry(ZipEntry(name))
                        val source = File(dir, name)
                        if (name.startsWith("mpv-")) {
                            copyScrubbed(source, out)
                        } else {
                            source.inputStream().use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry("system-info.txt"))
                    out.write(scrub(extraText).toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
                pruneOld(dir, "Pear-diagnostics-", ".zip", KEEP_DIAGNOSTICS)
                zip.absolutePath
            } catch (_: Throwable) {
                null
            }
        }

        /** Which files of the logs folder go in the zip. Never a `.seen-` file, and never the crash-report seen mark. */
        private fun inExport(name: String): Boolean {
            if (name.contains(".seen-") || name == "crash-seen.txt") return false
            return name.startsWith("pear.log") ||
                (name.startsWith("trace-") && name.endsWith(".log")) ||
                (name.startsWith("mpv-") && name.endsWith(".log")) ||
                (name.startsWith("crash-") && name.endsWith(".txt")) ||
                name == "last-crash.txt" ||
                (name.startsWith("unclean-") && name.endsWith(".txt"))
        }

        /** Copies an mpv log into the zip, line by line, with [scrub] applied. */
        private fun copyScrubbed(source: File, out: ZipOutputStream) {
            val writer = OutputStreamWriter(out, Charsets.UTF_8)
            source.useLines(Charsets.UTF_8) { lines ->
                for (line in lines) writer.write(scrub(line) + "\n")
            }
            // Flushed, not closed: closing the writer would close the zip.
            writer.flush()
        }

        /** Text that may leave the machine: a stream address keeps only its host and path (see [safeLogUrl]), and a user's folder name goes. */
        private fun scrub(text: String): String {
            val noUrls = URL_IN_TEXT.replace(text) { safeLogUrl(it.value) }
            return USER_PATH.replace(noUrls) { "${it.groupValues[1]}…" }
        }

        /** Keeps the newest [keep] files named `prefix…suffix` in [dir] and deletes the rest. */
        private fun pruneOld(dir: File, prefix: String, suffix: String, keep: Int) {
            dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(suffix) }
                ?.sortedByDescending { it.name }
                ?.drop(keep)
                ?.forEach { f -> runCatching { f.delete() } }
        }

        private fun startDaemon(name: String, body: () -> Unit): Thread =
            Thread({ runCatching { body() } }, name).apply { isDaemon = true; start() }

        private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    }
}

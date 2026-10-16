package com.mediahub.pear.log

import com.mediahub.pear.shell.CrashReport
import com.mediahub.pear.shell.CrashReportSource
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

/**
 * The file logger and crash briefing for the JVM builds (desktop and Android). [init] once at startup: it opens `pear.log`
 * in the given folder, routes [PearLog] lines to it, and writes a briefing whenever the app dies with an uncaught exception.
 * Nothing here throws; every entry point is guarded. Plain JVM only: no java.awt and no java.lang.management, which Android
 * does not have.
 */
object PearLogFile : LogSink {
    private const val LOG_NAME = "pear.log"
    private const val LOCK_NAME = "session.lock"
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MEMORY_LINES = 300
    private const val QUEUE_LIMIT = 2000
    private const val KEEP_CRASH_FILES = 10
    private const val UNCLEAN_TAIL_LINES = 200

    private val lineTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private var dir: File? = null
    private var trackSessions = false
    private var startedAt = 0L
    private var initialised = false
    private val recent = ArrayDeque<String>()
    private val contextMap = LinkedHashMap<String, String>()
    private val queue = LinkedBlockingQueue<String>()

    /** The folder this run logs to, or null before [init] has run. */
    fun logsDir(): File? = dir

    /**
     * Starts the file logger. [trackSessions] is true on desktop, where the window's close hook calls [markCleanExit]; on Android
     * there is no such hook, so it is false and only crash reports are written. Safe to call twice: the second call does nothing.
     */
    @Synchronized
    fun init(dir: File, appVersion: String, platform: String, trackSessions: Boolean) {
        if (initialised) return
        initialised = true
        runCatching {
            dir.mkdirs()
            this.dir = dir
            this.trackSessions = trackSessions
            startedAt = System.currentTimeMillis()
            if (trackSessions) {
                val lock = File(dir, LOCK_NAME)
                // Before this run writes anything, so the tail read below is the previous run's log.
                if (lock.exists()) runCatching { writeUncleanReport(dir, lock) }
                lock.writeText(LocalDateTime.now().format(lineTime))
            }
            PearLog.sink = this
            write('I', "Start", headerLine(appVersion, platform), null)
            Thread(Runnable { writerLoop() }, "pear-log").apply { isDaemon = true; start() }
            installCrashHandler()
        }
    }

    /** Called when the window closes cleanly, so the next launch does not report an unclean exit. */
    fun markCleanExit() {
        runCatching { dir?.let { File(it, LOCK_NAME).delete() } }
    }

    override fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        val text = buildString {
            append(LocalDateTime.now().format(lineTime)).append(' ').append(level).append(" [").append(tag).append("] ").append(msg)
            if (t != null) append('\n').append(t.stackTraceToString().trimEnd())
        }
        synchronized(recent) {
            recent.addLast(text)
            while (recent.size > MEMORY_LINES) recent.removeFirst()
        }
        // Never block the UI: when the writer falls far behind, the oldest line goes.
        if (queue.size >= QUEUE_LIMIT) queue.poll()
        queue.offer(text)
    }

    override fun context(key: String, value: String?) {
        synchronized(contextMap) {
            if (value == null) contextMap.remove(key) else contextMap[key] = value
        }
    }

    private fun writerLoop() {
        while (true) {
            val batch = ArrayList<String>()
            batch.add(queue.take())
            queue.drainTo(batch)
            runCatching { writeBatch(batch) }
        }
    }

    private fun writeBatch(lines: List<String>) {
        val folder = dir ?: return
        val file = File(folder, LOG_NAME)
        if (file.length() > MAX_FILE_BYTES) rotate(folder)
        file.appendText(lines.joinToString("") { it + "\n" }, Charsets.UTF_8)
    }

    /** pear.log becomes pear.log.1, and the older ones shift up. At most three old files are kept. */
    private fun rotate(folder: File) {
        File(folder, "$LOG_NAME.3").delete()
        File(folder, "$LOG_NAME.2").renameTo(File(folder, "$LOG_NAME.3"))
        File(folder, "$LOG_NAME.1").renameTo(File(folder, "$LOG_NAME.2"))
        File(folder, LOG_NAME).renameTo(File(folder, "$LOG_NAME.1"))
    }

    private fun headerLine(appVersion: String, platform: String): String {
        val rt = Runtime.getRuntime()
        return "Pear $appVersion on $platform; os=${System.getProperty("os.name")} ${System.getProperty("os.version")}; " +
            "java=${System.getProperty("java.version")}; maxHeap=${rt.maxMemory() / MB} MB; cpus=${rt.availableProcessors()}"
    }

    /** The previous run ended without a clean close and without a Java crash report: write what is known about it. */
    private fun writeUncleanReport(folder: File, lock: File) {
        val tail = File(folder, LOG_NAME).takeIf { it.isFile }?.readLines()?.takeLast(UNCLEAN_TAIL_LINES).orEmpty()
        val nativeDumps = (listOfNotNull(File(".").listFiles(), folder.listFiles()).flatMap { it.toList() })
            .filter { it.name.startsWith("hs_err_pid") && it.name.endsWith(".log") }
            .distinctBy { it.absolutePath }
            .map { "${it.name} (${it.absolutePath})" }
        val text = buildString {
            appendLine("Pear. did not close cleanly last time.")
            appendLine("This run found the session marker left by the previous one, and no crash report.")
            appendLine("Previous run started: ${runCatching { lock.readText() }.getOrDefault("unknown")}")
            appendLine("Native crash files: ${if (nativeDumps.isEmpty()) "none" else nativeDumps.joinToString("; ")}")
            appendLine()
            appendLine("Last ${tail.size} log lines of the previous run:")
            tail.forEach { appendLine(it) }
        }
        File(folder, "unclean-${LocalDateTime.now().format(fileTime)}.txt").writeText(text)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching { writeCrash(thread, t) }
            runCatching {
                if (previous != null) {
                    previous.uncaughtException(thread, t)
                } else {
                    System.err.println("Exception in thread \"${thread.name}\"")
                    t.printStackTrace()
                }
            }
        }
    }

    /** The briefing: summary, app facts, context, stack trace, the last log lines, and the live thread names. */
    private fun writeCrash(thread: Thread, t: Throwable) {
        val folder = dir ?: return
        val now = LocalDateTime.now()
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / MB
        val text = buildString {
            appendLine("${t::class.java.name}: ${t.message ?: "(no message)"}")
            appendLine("Thread: ${thread.name}")
            appendLine("Time: ${now.format(lineTime)}")
            appendLine("Uptime: ${(System.currentTimeMillis() - startedAt) / 1000} s")
            appendLine()
            appendLine("App: ${System.getProperty("os.name")} ${System.getProperty("os.version")}, java ${System.getProperty("java.version")}")
            appendLine("Memory: max ${rt.maxMemory() / MB} MB, used $used MB, cpus ${rt.availableProcessors()}")
            appendLine()
            appendLine("Context:")
            synchronized(contextMap) { contextMap.forEach { (k, v) -> appendLine("  $k = $v") } }
            appendLine()
            appendLine("Stack trace:")
            appendLine(t.stackTraceToString().trimEnd())
            appendLine()
            appendLine("Last log lines:")
            synchronized(recent) { recent.forEach { appendLine(it) } }
            appendLine()
            appendLine("Live threads:")
            Thread.getAllStackTraces().keys.map { it.name }.sorted().forEach { appendLine("  $it") }
        }
        val stamp = now.format(fileTime)
        File(folder, "crash-$stamp.txt").writeText(text)
        File(folder, "last-crash.txt").writeText(text)
        File(folder, LOG_NAME).appendText("${now.format(lineTime)} E [Crash] ${t::class.java.name} on ${thread.name}, report crash-$stamp.txt\n")
        trimCrashFiles(folder)
        // The crash has its own report now, so the next launch must not also report an unclean exit.
        runCatching { File(folder, LOCK_NAME).delete() }
    }

    private fun trimCrashFiles(folder: File) {
        folder.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_CRASH_FILES)
            ?.forEach { it.delete() }
    }

    private const val MB = 1024L * 1024L
}

/**
 * Shows the report from the last run. [folderOpener] opens the logs folder in the file manager, or is null where the
 * platform cannot (Android). Dismissing renames the shown file to `*.seen`, so it is not shown again.
 */
class FileCrashReportSource(
    private val dir: File,
    private val folderOpener: ((File) -> Unit)?,
) : CrashReportSource {
    private var shown: File? = null

    override fun pending(): CrashReport? {
        val file = File(dir, "last-crash.txt").takeIf { it.isFile } ?: newestUnclean() ?: return null
        shown = file
        return CrashReport("Pear. closed unexpectedly last time", file.readText())
    }

    override fun dismiss() {
        runCatching { shown?.let { it.renameTo(File(it.path + ".seen")) } }
        shown = null
    }

    override val canOpenFolder: Boolean
        get() = folderOpener != null

    override fun openFolder(): Boolean {
        val opener = folderOpener ?: return false
        return runCatching { opener(dir) }.isSuccess
    }

    private fun newestUnclean(): File? =
        dir.listFiles { f -> f.name.startsWith("unclean-") && f.name.endsWith(".txt") }?.maxByOrNull { it.name }
}

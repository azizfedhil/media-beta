package com.mediahub.pear.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.mediahub.pear.update.PearBuild
import java.awt.Desktop
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

private const val MAX_STALL_DUMPS = 60
private const val MB = 1024L * 1024L

/**
 * Desktop wiring for the flicker trace. [install] once at startup, after the file logger. It sets up the file sink, the
 * Settings hooks and the hotkeys. The EDT watchdog and the GC sampler run only while tracing is on (see [ProbedSink]).
 */
object TraceDesktop {
    private var installed = false

    @Synchronized
    fun install(logsDir: File) {
        if (installed) return
        installed = true
        runCatching {
            val file = PearTraceFile(PearBuild.VERSION, "desktop")
            file.headerExtra = { desktopHeaderLines() }
            PearTrace.sink = ProbedSink(file)
            Diagnostics.openLogsFolder = { openFolderInBackground(logsDir) }
            Diagnostics.exportZip = { exportAndOpen(logsDir) }
            installHotkeys()
            // Closing the app while tracing still writes the last lines and the STOP line.
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { PearTrace.stop() } }, "pear-trace-shutdown"))
        }
    }

    /** F8 marks the moment (only while tracing). Ctrl+Shift+F9 toggles tracing. Keys are never consumed. */
    private fun installHotkeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                if (e.keyCode == KeyEvent.VK_F8 && PearTrace.enabled) {
                    PearTrace.event("UI") { "hotkey F8" }
                    PearTrace.mark()
                } else if (e.keyCode == KeyEvent.VK_F9 && e.isControlDown && e.isShiftDown) {
                    toggleInBackground()
                }
            }
            false
        }
    }

    /** Starting or stopping writes to the disk, so it runs off the event thread. */
    private fun toggleInBackground() {
        Thread({ if (PearTrace.enabled) PearTrace.stop() else PearTrace.start() }, "pear-trace-toggle").apply {
            isDaemon = true
            start()
        }
    }

    private fun openFolderInBackground(dir: File) {
        Thread({ openFolder(dir) }, "pear-trace-open").apply {
            isDaemon = true
            start()
        }
    }

    private fun openFolder(dir: File) {
        if (Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().open(dir) }
    }

    private fun exportAndOpen(logsDir: File): String? {
        val path = PearTraceFile.exportZip(desktopHeaderLines().joinToString("\n"))
        if (path != null) openFolder(logsDir)
        return path
    }

    /** Screens, the render API, the JVM arguments. Written at the top of each trace, and into the diagnostics zip. */
    private fun desktopHeaderLines(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            for (dev in GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices) {
                val cfg = dev.defaultConfiguration
                val mode = dev.displayMode
                val b = cfg.bounds
                out.add(
                    "screen id=${dev.getIDstring()} bounds=${b.width}x${b.height}+${b.x}+${b.y} " +
                        "scale=${cfg.defaultTransform.scaleX} refresh=${mode.refreshRate}Hz bits=${mode.bitDepth}",
                )
            }
        }
        for (key in listOf("skiko.renderApi", "sun.java2d.d3d", "sun.java2d.opengl", "sun.java2d.noddraw", "sun.java2d.uiScale")) {
            out.add("prop $key=${System.getProperty(key) ?: "unset"}")
        }
        out.add("env SKIKO_RENDER_API=${System.getenv("SKIKO_RENDER_API") ?: "unset"}")
        runCatching { out.add("jvm args=${ManagementFactory.getRuntimeMXBean().inputArguments}") }
        return out
    }
}

/**
 * The sink the desktop installs: the file, plus the two probes that run only while the file is open. Start the file first,
 * then the probes; stop the probes first, then the file.
 */
private class ProbedSink(private val file: PearTraceFile) : TraceSink by file {
    override fun start(): Boolean {
        val ok = file.start()
        if (ok) TraceProbes.start()
        return ok
    }

    override fun stop() {
        TraceProbes.stop()
        file.stop()
    }
}

/**
 * The EDT watchdog and the GC/heap sampler. Two daemon threads, created by [start] and ended by [stop]. Nothing runs while
 * tracing is off.
 */
private object TraceProbes {
    @Volatile private var running = false
    private var edt: Thread? = null
    private var gc: Thread? = null

    /** When the watchdog's last probe was posted (System.nanoTime), or 0 when none is pending. */
    private val pendingSince = AtomicLong(0L)

    fun start() {
        if (running) return
        running = true
        edt = daemon("pear-trace-edt") { edtWatch() }
        gc = daemon("pear-trace-gc") { gcSample() }
    }

    fun stop() {
        running = false
        edt?.interrupt()
        gc?.interrupt()
        edt = null
        gc = null
        pendingSince.set(0L)
    }

    /** Every 100 ms: one probe on the event queue. A slow answer is logged, and a probe still unanswered after 100 ms gets a stack. */
    private fun edtWatch() {
        var dumps = 0
        var stallLogged = false
        while (running) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val since = pendingSince.get()
            if (since == 0L) {
                stallLogged = false
                val posted = System.nanoTime()
                pendingSince.set(posted)
                SwingUtilities.invokeLater {
                    val ms = (System.nanoTime() - posted) / 1_000_000.0
                    pendingSince.set(0L)
                    PearTrace.sample("edt.latencyMs", ms)
                    if (ms > 100.0) {
                        PearTrace.count("edt.stalls")
                        PearTrace.event("EDT") { "UI thread was blocked for ${ms.toLong()} ms" }
                    }
                }
            } else {
                val waitedMs = (System.nanoTime() - since) / 1_000_000.0
                if (waitedMs > 100.0 && !stallLogged && dumps < MAX_STALL_DUMPS) {
                    stallLogged = true
                    dumps++
                    PearTrace.event("EDT") { "STALL in progress ${waitedMs.toLong()} ms, stack:\n" + edtStack() }
                }
            }
        }
    }

    /**
     * The stall report, as one block: the EDT's state, its top 24 frames, and the lock it waits on. If a thread owns that lock,
     * its state and top 12 frames follow. Then the top 10 frames of each mpv thread that is alive. Never throws.
     */
    private fun edtStack(): String = runCatching {
        val all = Thread.getAllStackTraces()
        val out = StringBuilder()
        val edt = all.keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
        if (edt == null) {
            out.append("        (no AWT-EventQueue thread found)\n")
        } else {
            val mx = ManagementFactory.getThreadMXBean()
            val info = runCatching { mx.getThreadInfo(edt.id, 24) }.getOrNull()
            out.append("        EDT state=${edt.state} lock=${info?.lockName ?: "none"} lockOwner=${info?.lockOwnerName ?: "none"}\n")
            all[edt]?.take(24)?.forEach { out.append("        at $it\n") }
            if (info != null && info.lockOwnerName != null) {
                val owner = runCatching { mx.getThreadInfo(info.lockOwnerId, 12) }.getOrNull()
                out.append("        lock owner \"${info.lockOwnerName}\" state=${owner?.threadState ?: "gone"}, top 12 frames:\n")
                owner?.stackTrace?.forEach { out.append("            at $it\n") }
            }
        }
        for (name in listOf("mpv-render", "mpv-event-loop", "mpv-commands")) {
            val t = all.keys.firstOrNull { it.name == name } ?: continue
            out.append("        $name state=${t.state}\n")
            all[t]?.take(10)?.forEach { out.append("            at $it\n") }
        }
        out.toString().trimEnd()
    }.getOrElse { "        (stack unavailable: ${it::class.simpleName})" }

    /** Once a second: GC count and time since the last second, and the heap in use. */
    private fun gcSample() {
        var lastCount = gcCount()
        var lastTime = gcTime()
        while (running) {
            try {
                Thread.sleep(1_000L)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val count = gcCount()
            val time = gcTime()
            val dCount = count - lastCount
            val dTime = time - lastTime
            lastCount = count
            lastTime = time
            if (dCount > 0) PearTrace.count("gc.count", dCount)
            PearTrace.sample("gc.ms", dTime.toDouble())
            val rt = Runtime.getRuntime()
            PearTrace.sample("heap.mb", ((rt.totalMemory() - rt.freeMemory()) / MB).toDouble())
            if (dTime > 50L) PearTrace.event("GC") { "GC pause ~$dTime ms in the last second" }
        }
    }

    private fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }

    private fun gcTime(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionTime) }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread({ runCatching { body() } }, name).apply {
            isDaemon = true
            start()
        }
}

/**
 * Watches the window for the things that can look like a flicker from the outside: resizes, moves, focus, state, and the
 * screen it is on (DPI or a different monitor). Its listeners are removed when the composable leaves.
 */
@Composable
fun TraceWindow(window: java.awt.Window) {
    DisposableEffect(window) {
        val probe = WindowProbe(window)
        window.addComponentListener(probe)
        window.addWindowListener(probe)
        window.addWindowFocusListener(probe)
        window.addWindowStateListener(probe)
        onDispose {
            window.removeComponentListener(probe)
            window.removeWindowListener(probe)
            window.removeWindowFocusListener(probe)
            window.removeWindowStateListener(probe)
        }
    }
}

private class WindowProbe(private val window: java.awt.Window) : WindowAdapter(), ComponentListener {
    private var lastDevice: String? = null
    private var lastScale = 0.0
    private var lastResizeNanos = 0L

    override fun componentResized(e: ComponentEvent) {
        if (!PearTrace.enabled) return
        PearTrace.count("win.resizes")
        val now = System.nanoTime()
        if (now - lastResizeNanos >= 100_000_000L) {
            lastResizeNanos = now
            PearTrace.event("Window") { "resized ${window.width}x${window.height}" }
        }
        checkScreen()
    }

    override fun componentMoved(e: ComponentEvent) {
        if (!PearTrace.enabled) return
        PearTrace.count("win.moves")
        checkScreen()
    }

    override fun componentShown(e: ComponentEvent) = Unit

    override fun componentHidden(e: ComponentEvent) = Unit

    override fun windowActivated(e: WindowEvent) {
        PearTrace.event("Window") { "activated" }
    }

    override fun windowDeactivated(e: WindowEvent) {
        PearTrace.event("Window") { "deactivated" }
    }

    override fun windowIconified(e: WindowEvent) {
        PearTrace.event("Window") { "iconified" }
    }

    override fun windowDeiconified(e: WindowEvent) {
        PearTrace.event("Window") { "deiconified" }
    }

    override fun windowGainedFocus(e: WindowEvent) {
        PearTrace.event("Window") { "focus gained" }
    }

    override fun windowLostFocus(e: WindowEvent) {
        PearTrace.event("Window") { "focus lost" }
    }

    override fun windowStateChanged(e: WindowEvent) {
        PearTrace.event("Window") { "state extendedState=${(window as? Frame)?.extendedState ?: -1}" }
    }

    /** Logs the screen the window is on when it differs from the last one seen (a move to another monitor, or a DPI change). */
    private fun checkScreen() {
        val gc = window.graphicsConfiguration ?: return
        val device = gc.device.getIDstring()
        val scale = gc.defaultTransform.scaleX
        if (device != lastDevice || scale != lastScale) {
            val label = if (lastDevice == null) "screen" else "SCREEN CHANGE"
            val b = gc.bounds
            PearTrace.event("Window") {
                "$label device=$device scale=$scale bounds=${b.width}x${b.height}+${b.x}+${b.y}"
            }
            lastDevice = device
            lastScale = scale
        }
    }
}

package com.mediahub.pear.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mediahub.pear.App
import com.mediahub.pear.data.AppDirs
import com.mediahub.pear.data.desktopStorage
import com.mediahub.pear.net.desktopNetwork
import com.mediahub.pear.player.mpv.LibmpvSetupGate
import com.mediahub.pear.player.mpv.PlayerWarmup
import com.mediahub.pear.glass.BatteryWatch
import com.mediahub.pear.log.FileCrashReportSource
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.log.PearLogFile
import com.mediahub.pear.log.PearTrace
import com.mediahub.pear.log.TraceDesktop
import com.mediahub.pear.log.TraceWindow
import com.mediahub.pear.shell.AppExit
import com.mediahub.pear.shell.AppLifecycle
import com.mediahub.pear.shell.CrashReports
import com.mediahub.pear.update.PearBuild
import com.mediahub.pear.update.UpdateNotice
import com.mediahub.pear.shell.NativeTitleBar
import com.mediahub.pear.shell.RememberWindowGeometry
import com.mediahub.pear.shell.WindowMemory
import com.mediahub.pear.shell.restoredWindow
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.shell.WindowMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import java.io.File

/**
 * Full screen is exclusive, so Windows minimizes the window when it loses focus, and it can come back small, still marked
 * Fullscreen. true: restoring puts the window back into full screen. false: restoring leaves full screen instead (the fallback).
 */
private const val KEEP_FULLSCREEN_AFTER_MINIMIZE = true

fun main(args: Array<String>) = application {
    startLogging(traceAtStart = "--trace" in args)
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("swing.aatext", "true")

    val smoke = "--smoke" in args
    if (smoke) LaunchedEffect(Unit) { delay(3000); exitApplication() }

    val storage = remember { desktopStorage() }
    val network = remember { desktopNetwork() }

    val opening = remember { restoredWindow(WindowMemory.read(storage.prefs)) }
    val windowState = rememberWindowState(
        placement = opening.placement,
        position = opening.position,
        size = opening.size,
    )
    val awtWindow = remember { arrayOfNulls<java.awt.Window>(1) }
    val control = remember {
        object : WindowControl {
            override val size: DpSize get() = windowState.size

            override var mode: WindowMode
                get() = when (windowState.placement) {
                    WindowPlacement.Fullscreen -> WindowMode.Fullscreen
                    WindowPlacement.Maximized -> WindowMode.Maximized
                    else -> WindowMode.Windowed
                }
                set(value) {
                    PearLog.w("Window", "mode $mode -> $value")
                    val target = when (value) {
                        WindowMode.Fullscreen -> WindowPlacement.Fullscreen
                        WindowMode.Maximized -> WindowPlacement.Maximized
                        WindowMode.Windowed -> WindowPlacement.Floating
                    }
                    if (windowState.placement == target) return

                    PearLog.context("window", value.name)
                    val id = ++changeId
                    SwingUtilities.invokeLater {
                        val win = awtWindow[0]
                        val was = windowState.placement
                        if (target == WindowPlacement.Fullscreen && was != WindowPlacement.Fullscreen) {
                            beforeFullscreen = if (was == WindowPlacement.Maximized) WindowMode.Maximized else WindowMode.Windowed
                        }
                        if (was == WindowPlacement.Fullscreen && target != WindowPlacement.Fullscreen) {
                            if (win != null && heldInFullscreen(win)) {
                                runCatching { win.graphicsConfiguration.device.fullScreenWindow = null }
                            }
                        }
                        windowState.placement = target
                        if (was == WindowPlacement.Fullscreen && target == WindowPlacement.Maximized) reassertMaximized(id)
                    }
                }

            /** The state the window had before it went full screen, so leaving full screen returns to it. */
            private var beforeFullscreen = WindowMode.Windowed

            /** Counts mode changes, so a re-check scheduled by an earlier change does nothing. */
            private var changeId = 0

            override val modeBeforeFullscreen: WindowMode get() = beforeFullscreen

            /**
             * Leaving full screen can bring the window back as a restored (floating) one. Three checks after the change put it back to
             * maximized, unless the person changed the window again in the meantime.
             */
            private fun reassertMaximized(id: Int) {
                for (delayMillis in listOf(80, 220, 500)) {
                    javax.swing.Timer(delayMillis) { _ ->
                        if (id == changeId && windowState.placement != WindowPlacement.Fullscreen) {
                            val frame = awtWindow[0] as? java.awt.Frame
                            val maximized = frame != null && (frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH) == java.awt.Frame.MAXIMIZED_BOTH
                            if (!maximized) {
                                windowState.placement = WindowPlacement.Maximized
                                frame?.extendedState = java.awt.Frame.MAXIMIZED_BOTH
                            }
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }

            override fun resize(width: Dp, height: Dp) {
                val screenHeight = Toolkit.getDefaultToolkit().screenSize.height
                val maxHeight = (screenHeight - 120).coerceAtLeast(600)
                windowState.size = DpSize(width, height.coerceAtMost(maxHeight.dp))
            }
        }
    }

    /**
     * Puts a window that came back from minimized as a small window, still marked Fullscreen, back into full screen. Windows
     * minimizes an exclusive full-screen window on focus loss, and nothing re-applies the screen bounds on restore. If the window
     * is still not full screen after a short wait, it leaves full screen instead, so no tiny window is left marked Fullscreen.
     */
    fun repairFullscreen(window: java.awt.Window) {
        PearLog.w("Window", "[Window] fullscreen repair: before=${window.width}x${window.height}")
        if (!KEEP_FULLSCREEN_AFTER_MINIMIZE) {
            control.mode = control.modeBeforeFullscreen
            return
        }
        val device = window.graphicsConfiguration?.device
        runCatching { device?.fullScreenWindow = null }
        runCatching { device?.fullScreenWindow = window }
        javax.swing.Timer(150) { _ ->
            // A change the person made within the wait is theirs to keep, so this check only acts while still full screen.
            if (windowState.placement == WindowPlacement.Fullscreen) {
                val screen = device?.defaultConfiguration?.bounds?.size
                val held = heldInFullscreen(window)
                if (screen == null || window.size != screen || !held) {
                    PearLog.w("Window", "fullscreen repair failed: after=${window.width}x${window.height} held=$held")
                    control.mode = control.modeBeforeFullscreen
                } else {
                    PearLog.i("Window", "[Window] fullscreen repair: after=${window.width}x${window.height}")
                }
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    val closeScope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val appIcon = remember { loadAppIcon() }
    // The window's close and the updater's "Update now" both end the app here: exit hooks, clean-exit mark, storage flush.
    val quit: () -> Unit = {
        if (!closing) {
            closing = true
            AppExit.runAll()
            PearLogFile.markCleanExit()
            storage.flush()
            // The window goes away at once. The process stays up for a moment so the last position reaches Pear.pair,
            // then the storage is flushed again, because that push updates its snapshot.
            closeScope.launch {
                AppExit.runFinishers(3_000L)
                storage.flush()
                exitApplication()
            }
        }
    }
    Window(
        onCloseRequest = quit,
        visible = !closing,
        title = "Pear.",
        state = windowState,
        icon = appIcon,
    ) {
        window.minimumSize = Dimension(360, 600)
        SideEffect { awtWindow[0] = window }
        DisposableEffect(window) {
            // Full screen is exclusive, so a restore can leave the window small and still marked Fullscreen. Repair it on restore.
            val listener = object : WindowAdapter() {
                override fun windowDeiconified(e: WindowEvent) {
                    if (windowState.placement != WindowPlacement.Fullscreen) return
                    // AWT also fires a spurious deiconify while ICONIFIED is still set. The real restore comes after it.
                    if (isIconified(window)) return
                    SwingUtilities.invokeLater {
                        if (windowState.placement == WindowPlacement.Fullscreen) repairFullscreen(window)
                    }
                }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        // Coming back to the window pulls what other devices saved (Pear.pair). The engine skips it right after a sync.
        DisposableEffect(window) {
            val focus = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) { AppLifecycle.notifyForegrounded() }
                override fun windowLostFocus(e: WindowEvent) {}
            }
            window.addWindowFocusListener(focus)
            onDispose { window.removeWindowFocusListener(focus) }
        }
        LaunchedEffect(windowState.placement, windowState.isMinimized) {
            PearLog.i("Window", "placement=${windowState.placement} minimized=${windowState.isMinimized} size=${windowState.size}")
        }
        BatteryWatch()
        NativeTitleBar(window)
        TraceWindow(window)
        RememberWindowGeometry(window, windowState, storage.prefs)
        // Windows: waits for libmpv (fetched on first run, or again if it went missing) before the app starts.
        LibmpvSetupGate {
            App(
                windowControl = control,
                storage = storage,
                network = network,
                overlay = { UpdateNotice(onQuit = quit) },
                warmPlayer = { PlayerWarmup.run() },
            )
        }
    }
}

/**
 * Starts the file log and the crash briefing before anything else runs. The logs live in the app's config folder, in logs/.
 * Nothing here can stop the app from starting: the logger never throws.
 */
private fun startLogging(traceAtStart: Boolean) {
    val logs = File(AppDirs.detect().config, "logs")
    PearLogFile.init(logs, PearBuild.VERSION, "desktop", trackSessions = true)
    val opener: ((File) -> Unit)? = if (Desktop.isDesktopSupported()) ::openInFileManager else null
    CrashReports.source = FileCrashReportSource(logs, opener)
    TraceDesktop.install(logs)
    // --trace starts the flicker trace at launch. Otherwise it is off until Settings or Ctrl+Shift+F9 turns it on.
    if (traceAtStart) PearTrace.start()
}

private fun openInFileManager(dir: File) {
    Desktop.getDesktop().open(dir)
}

/**
 * The Pear icon for the title bar and the taskbar, from `src/main/resources/pear.png` (made by tools/make_icons.py).
 * Null if the file is not in the build: the window then keeps the default icon, and the app still starts.
 */
private fun loadAppIcon(): Painter? {
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("pear.png") ?: return null
    return runCatching { stream.use { BitmapPainter(loadImageBitmap(it)) } }.getOrNull()
}

private fun heldInFullscreen(window: java.awt.Window): Boolean =
    runCatching { window.graphicsConfiguration.device.fullScreenWindow === window }.getOrDefault(false)

/** True while the window is minimized, by AWT's ICONIFIED bit in its extended state. A window that is not a Frame is never minimized here. */
private fun isIconified(window: java.awt.Window): Boolean =
    (((window as? java.awt.Frame)?.extendedState ?: 0) and java.awt.Frame.ICONIFIED) != 0

package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.mediahub.pear.data.PrefStore
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer

/** Where the window opens: its placement, size and position. */
class RestoredWindow(val placement: WindowPlacement, val size: DpSize, val position: WindowPosition)

private val DEFAULT_SIZE = DpSize(1280.dp, 800.dp)

/**
 * The window as it opens (Batch 4.6c). The size is kept inside the primary screen. A saved place that is off every screen
 * (a monitor was unplugged, say) is dropped, and the window is centred instead.
 */
fun restoredWindow(saved: SavedWindow?, screens: List<ScreenBounds> = screenBounds()): RestoredWindow {
    val placement = when (saved?.mode) {
        WindowMode.Maximized -> WindowPlacement.Maximized
        // Never opens full screen (see WindowMemory.openingMode); read() already maps a saved one, this guards direct calls.
        WindowMode.Fullscreen -> WindowPlacement.Maximized
        WindowMode.Windowed, null -> WindowPlacement.Floating
    }
    val centred = WindowPosition(Alignment.Center)
    val geometry = saved?.geometry ?: return RestoredWindow(placement, DEFAULT_SIZE, centred)

    val primary = screens.firstOrNull()
    val (width, height) = if (primary != null) {
        WindowMemory.fit(geometry.width, geometry.height, primary)
    } else {
        geometry.width to geometry.height
    }
    val fitted = geometry.copy(width = width, height = height)
    val onScreen = screens.isNotEmpty() && WindowMemory.titleBarOnScreen(fitted, screens)
    val position = if (onScreen) WindowPosition.Absolute(fitted.x.dp, fitted.y.dp) else centred
    return RestoredWindow(placement, DpSize(width.dp, height.dp), position)
}

/** Every screen's bounds in dp. Each screen is scaled by its own factor, so mixed-scale setups read correctly. */
fun screenBounds(): List<ScreenBounds> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val config = device.defaultConfiguration
        val scale = config.defaultTransform.scaleX.takeIf { it > 0 } ?: 1.0
        val b = config.bounds
        ScreenBounds(b.x / scale, b.y / scale, b.width / scale, b.height / scale)
    }
}.getOrDefault(emptyList())

/**
 * Keeps the window's mode and floating geometry in the settings (Batch 4.6c). A move or a resize is written after a short
 * pause, so dragging doesn't write on every pixel, and everything is written on the way out (AppExit, before the settings
 * are flushed). A maximized or full-screen window stores its mode only; its floating geometry is the one from before.
 * A full-screen window is saved as the mode it was in before full screen, and a window never opens full screen.
 */
@Composable
fun RememberWindowGeometry(window: Window, state: WindowState, prefs: PrefStore) {
    DisposableEffect(window, state, prefs) {
        val tracker = WindowMemoryTracker(window, state, prefs)
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = tracker.changed()
            override fun componentResized(e: ComponentEvent) = tracker.changed()
        }
        window.addComponentListener(listener)
        val unregister = AppExit.register { tracker.flush() }
        onDispose {
            window.removeComponentListener(listener)
            unregister()
            tracker.flush()
        }
    }
}

private class WindowMemoryTracker(
    private val window: Window,
    private val state: WindowState,
    private val prefs: PrefStore,
) {
    // A Swing timer runs on the UI thread, and restarting it is what makes the pause a debounce.
    private val timer = Timer(PAUSE_MILLIS) { flush() }.apply { isRepeats = false }

    fun changed() = timer.restart()

    // The last mode the window was in that was not full screen: what a full-screen window is saved as.
    private var beforeFullscreen: WindowMode = when (state.placement) {
        WindowPlacement.Floating -> WindowMode.Windowed
        else -> WindowMode.Maximized
    }

    fun flush() {
        timer.stop()
        val current = when (state.placement) {
            WindowPlacement.Maximized -> WindowMode.Maximized
            WindowPlacement.Fullscreen -> WindowMode.Fullscreen
            else -> WindowMode.Windowed
        }
        if (current != WindowMode.Fullscreen) beforeFullscreen = current
        val geometry = if (state.placement == WindowPlacement.Floating) floatingGeometry() else null
        WindowMemory.write(prefs, WindowMemory.savedMode(current, beforeFullscreen), geometry)
    }

    /** The window's place and size in dp. AWT reports pixels, so each is divided by the window's scale factor. */
    private fun floatingGeometry(): WindowGeometry {
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
        val at = window.location
        val size = window.size
        return WindowGeometry(at.x / scale, at.y / scale, size.width / scale, size.height / scale)
    }

    private companion object {
        const val PAUSE_MILLIS = 400
    }
}

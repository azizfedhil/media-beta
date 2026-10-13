package com.mediahub.pear.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mediahub.pear.App
import com.mediahub.pear.data.desktopStorage
import com.mediahub.pear.net.desktopNetwork
import com.mediahub.pear.glass.BatteryWatch
import com.mediahub.pear.shell.AppExit
import com.mediahub.pear.shell.NativeTitleBar
import com.mediahub.pear.shell.RememberWindowGeometry
import com.mediahub.pear.shell.WindowMemory
import com.mediahub.pear.shell.restoredWindow
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.shell.WindowMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Toolkit

fun main(args: Array<String>) = application {
    // Enable subpixel / LCD font smoothing for AWT
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("swing.aatext", "true")
    // CI smoke test: open the window, stay for 3 s, exit 0.
    val smoke = "--smoke" in args
    if (smoke) LaunchedEffect(Unit) { delay(3000); exitApplication() }

    // Settings and secrets live in the user's config folder (see docs/desktop/storage.md).
    val storage = remember { desktopStorage() }
    // HTTP (OkHttp), the answer cache and the permanent TMDB facts (see docs/desktop/metadata.md).
    val network = remember { desktopNetwork() }

    // The window as it was last left (Batch 4.6c): its size and place, and whether it was maximized or full screen.
    val opening = remember { restoredWindow(WindowMemory.read(storage.prefs)) }
    val windowState = rememberWindowState(
        placement = opening.placement,
        position = opening.position,
        size = opening.size,
    )
    // The AWT window behind windowState, set once the window exists. Full screen is checked against it (see below).
    val awtWindow = remember { arrayOfNulls<java.awt.Window>(1) }
    val scope = rememberCoroutineScope()
    val control = remember {
        object : WindowControl {
            // A leave-full-screen check that is still waiting; any newer change of mode cancels it.
            private var pending: Job? = null

            override val size: DpSize get() = windowState.size

            override var mode: WindowMode
                get() = when (windowState.placement) {
                    WindowPlacement.Fullscreen -> WindowMode.Fullscreen
                    WindowPlacement.Maximized -> WindowMode.Maximized
                    else -> WindowMode.Windowed
                }
                set(value) {
                    val target = when (value) {
                        WindowMode.Fullscreen -> WindowPlacement.Fullscreen
                        WindowMode.Maximized -> WindowPlacement.Maximized
                        WindowMode.Windowed -> WindowPlacement.Floating
                    }
                    pending?.cancel()
                    pending = null
                    val leaving = windowState.placement == WindowPlacement.Fullscreen && target != WindowPlacement.Fullscreen
                    windowState.placement = target
                    if (leaving) pending = scope.launch { insistOnLeavingFullscreen(target) }
                }

            /**
             * Full screen is exclusive mode on the screen's device, and a window can stay in it after the state says it left
             * (the window then covers the screen and nothing can be clicked out of it). Shortly after leaving, if the
             * device still holds the window, release it by hand and apply the target again, one step at a time.
             */
            private suspend fun insistOnLeavingFullscreen(target: WindowPlacement) {
                delay(300)
                val window = awtWindow[0] ?: return
                if (!heldInFullscreen(window)) return
                runCatching { window.graphicsConfiguration.device.fullScreenWindow = null }
                windowState.placement = WindowPlacement.Floating
                delay(150)
                windowState.placement = target
            }

            override fun resize(width: Dp, height: Dp) {
                // Keep the window on screen: laptops can be shorter than a phone-sized window.
                val screenHeight = Toolkit.getDefaultToolkit().screenSize.height
                val maxHeight = (screenHeight - 120).coerceAtLeast(600)
                windowState.size = DpSize(width, height.coerceAtMost(maxHeight.dp))
            }
        }
    }

    Window(
        // Hooks first (the player banks its resume point), then the settings are written to disk.
        onCloseRequest = { AppExit.runAll(); storage.flush(); exitApplication() },
        title = "Pear.",
        state = windowState,
    ) {
        window.minimumSize = Dimension(360, 600) // small enough for the phone layout
        SideEffect { awtWindow[0] = window }
        BatteryWatch()
        NativeTitleBar(window) // the window's own title bar, dark to match the app (Windows)
        RememberWindowGeometry(window, windowState, storage.prefs)
        App(windowControl = control, storage = storage, network = network)
    }
}

/** True while the screen's device still holds [window] as its full-screen window. */
private fun heldInFullscreen(window: java.awt.Window): Boolean =
    runCatching { window.graphicsConfiguration.device.fullScreenWindow === window }.getOrDefault(false)

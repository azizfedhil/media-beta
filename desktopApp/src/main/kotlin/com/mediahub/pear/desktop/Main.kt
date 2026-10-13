package com.mediahub.pear.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.SwingUtilities

fun main(args: Array<String>) = application {
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
                    val target = when (value) {
                        WindowMode.Fullscreen -> WindowPlacement.Fullscreen
                        WindowMode.Maximized -> WindowPlacement.Maximized
                        WindowMode.Windowed -> WindowPlacement.Floating
                    }
                    if (windowState.placement == target) return

                    SwingUtilities.invokeLater {
                        val win = awtWindow[0]
                        if (windowState.placement == WindowPlacement.Fullscreen && target != WindowPlacement.Fullscreen) {
                            if (win != null && heldInFullscreen(win)) {
                                runCatching { win.graphicsConfiguration.device.fullScreenWindow = null }
                            }
                        }
                        windowState.placement = target
                    }
                }

            override fun resize(width: Dp, height: Dp) {
                val screenHeight = Toolkit.getDefaultToolkit().screenSize.height
                val maxHeight = (screenHeight - 120).coerceAtLeast(600)
                windowState.size = DpSize(width, height.coerceAtMost(maxHeight.dp))
            }
        }
    }

    Window(
        onCloseRequest = { AppExit.runAll(); storage.flush(); exitApplication() },
        title = "Pear.",
        state = windowState,
    ) {
        window.minimumSize = Dimension(360, 600)
        SideEffect { awtWindow[0] = window }
        BatteryWatch()
        NativeTitleBar(window)
        RememberWindowGeometry(window, windowState, storage.prefs)
        App(windowControl = control, storage = storage, network = network)
    }
}

private fun heldInFullscreen(window: java.awt.Window): Boolean =
    runCatching { window.graphicsConfiguration.device.fullScreenWindow === window }.getOrDefault(false)
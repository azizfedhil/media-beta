package com.mediahub.pear.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mediahub.pear.App
import com.mediahub.pear.data.desktopStorage
import com.mediahub.pear.net.desktopNetwork
import com.mediahub.pear.shell.WindowControl
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.Toolkit

fun main(args: Array<String>) = application {
    // CI smoke test: open the window, stay for 3 s, exit 0.
    val smoke = "--smoke" in args
    if (smoke) LaunchedEffect(Unit) { delay(3000); exitApplication() }

    // Settings and secrets live in the user's config folder (see docs/desktop/storage.md).
    val storage = remember { desktopStorage() }
    // HTTP (OkHttp), the answer cache and the permanent TMDB facts (see docs/desktop/metadata.md).
    val network = remember { desktopNetwork() }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    val control = remember {
        object : WindowControl {
            override val size: DpSize get() = windowState.size

            override fun resize(width: Dp, height: Dp) {
                // Keep the window on screen: laptops can be shorter than a phone-sized window.
                val screenHeight = Toolkit.getDefaultToolkit().screenSize.height
                val maxHeight = (screenHeight - 120).coerceAtLeast(600)
                windowState.size = DpSize(width, height.coerceAtMost(maxHeight.dp))
            }
        }
    }

    Window(
        onCloseRequest = { storage.flush(); exitApplication() },
        title = "Pear.",
        state = windowState,
    ) {
        window.minimumSize = Dimension(360, 600) // small enough for the phone layout
        App(windowControl = control, storage = storage, network = network)
    }
}

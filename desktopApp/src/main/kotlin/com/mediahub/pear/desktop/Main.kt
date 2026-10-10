package com.mediahub.pear.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mediahub.pear.App
import kotlinx.coroutines.delay
import java.awt.Dimension

fun main(args: Array<String>) = application {
    // CI smoke test: open the window, stay for 3 s, exit 0.
    val smoke = "--smoke" in args
    if (smoke) LaunchedEffect(Unit) { delay(3000); exitApplication() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Pear.",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        window.minimumSize = Dimension(360, 600) // small enough to try the phone layout (Batch 1.4)
        App()
    }
}

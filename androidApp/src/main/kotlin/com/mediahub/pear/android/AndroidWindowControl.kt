package com.mediahub.pear.android

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mediahub.pear.shell.WindowControl
import com.mediahub.pear.shell.WindowMode

/**
 * The phone's window. It is the screen, so [resize] does nothing. Full screen hides the system bars and turns the screen
 * to landscape (the player's immersive view). Leaving it shows the bars and gives the orientation back to the system.
 */
class AndroidWindowControl(private val activity: Activity) : WindowControl {

    override val size: DpSize
        get() {
            val metrics = activity.resources.displayMetrics
            return DpSize((metrics.widthPixels / metrics.density).dp, (metrics.heightPixels / metrics.density).dp)
        }

    override fun resize(width: Dp, height: Dp) = Unit

    // State-backed, so a change recomposes whatever reads the mode (the safe-area padding does).
    private var current by mutableStateOf(WindowMode.Windowed)

    override var mode: WindowMode
        get() = current
        set(value) {
            if (value == current) return
            current = value
            applyMode(value)
        }

    private fun applyMode(value: WindowMode) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (value == WindowMode.Fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

package com.mediahub.pear.android

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Rational
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.mediahub.pear.App
import com.mediahub.pear.AndroidHost
import com.mediahub.pear.glass.FramePacing
import com.mediahub.pear.glass.PowerStatus
import com.mediahub.pear.net.androidNetwork
import com.mediahub.pear.player.PipState
import com.mediahub.pear.player.ui.LocalPlayerInsets
import com.mediahub.pear.player.ui.PlayerInsets
import com.mediahub.pear.shell.AppExit
import com.mediahub.pear.shell.AppLifecycle
import com.mediahub.pear.shell.WindowMode
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    // Held by the application, so a recreated activity uses the same store (see PearApplication.storage).
    private val storage get() = (application as PearApplication).storage
    private val network by lazy { androidNetwork() }
    private val control by lazy { AndroidWindowControl(this) }
    private var powerReceiver: BroadcastReceiver? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    companion object {
        /** Frame timings are delivered on this thread, not the UI thread. Made on first use and kept: it is cheap. */
        private val frameHandler: Handler by lazy { Handler(HandlerThread("pear-frames").apply { start() }.looper) }
    }

    // systemBarsIgnoringVisibility is marked experimental in Compose; the CI compile reported it (MainActivity.kt:71).
    // Opted in for this function only, as the shared module does for its layout APIs (PearControls.kt).
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidHost.activity = WeakReference(this)
        PipState.openWindow = { enterPip() }
        enableEdgeToEdge()
        setContent {
            // The window's parameters follow the player: the shape of the picture, and whether leaving the app enters
            // Picture in Picture by itself (Android 12 and later).
            LaunchedEffect(PipState.wanted, PipState.videoAspect) { setPictureInPictureParams(pipParams()) }

            // The status and navigation bars stay clear of the app, except while the player is full screen.
            val fullscreen = control.mode == WindowMode.Fullscreen
            val safe = if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            // Full screen the video is edge to edge, but the player's controls must clear the notch, the rounded corners and
            // the gesture bar. Outside full screen the app is already padded by safeDrawing, so none are passed on.
            val density = LocalDensity.current
            // The CI compile reported one error in this file (the experimental member, opted in on onCreate) and no other.
            // The bars are hidden in full screen, so the plain sets would read zero: the ...IgnoringVisibility set is needed.
            val systemAreas = WindowInsets.displayCutout
                .union(WindowInsets.systemBarsIgnoringVisibility)
                .union(WindowInsets.systemGestures)
            val playerInsets = if (fullscreen) {
                PlayerInsets(
                    left = systemAreas.getLeft(density, LayoutDirection.Ltr) / density.density,
                    top = systemAreas.getTop(density) / density.density,
                    right = systemAreas.getRight(density, LayoutDirection.Ltr) / density.density,
                    bottom = systemAreas.getBottom(density) / density.density,
                )
            } else {
                PlayerInsets()
            }
            CompositionLocalProvider(LocalPlayerInsets provides playerInsets) {
                Box(Modifier.fillMaxSize().then(safe)) {
                    App(windowControl = control, devTools = isDebuggable(), storage = storage, network = network)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            // The first frame after the window appears is always slow: not counted.
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) FramePacing.frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        frameListener = listener
        window.addOnFrameMetricsAvailableListener(listener, frameHandler)
        watchPowerSave()
        watchThermal()
        AppLifecycle.notifyForegrounded()
    }

    override fun onStop() {
        unwatchPowerSave()
        unwatchThermal()
        // A Picture in Picture window keeps the film going, so only an app that is really hidden counts as left.
        if (!isInPictureInPictureMode) AppLifecycle.notifyBackgrounded()
        storage.flush()
        frameListener?.let { window.removeOnFrameMetricsAvailableListener(it) }
        frameListener = null
        super.onStop()
    }

    override fun onDestroy() {
        PipState.openWindow = null
        // Only when the user is leaving: a rotation or a configuration change keeps the same activity running.
        if (isFinishing) {
            AppExit.runAll()
            storage.flush()
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 and later enter Picture in Picture by themselves (see pipParams). Older versions need the call here.
        if (PipState.wanted && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.active = isInPictureInPictureMode
    }

    private fun enterPip() {
        if (!PipState.wanted) return
        enterPictureInPictureMode(pipParams())
    }

    /** The window keeps the picture's shape, within the range Android allows (1:2.39 to 2.39:1). */
    private fun pipParams(): PictureInPictureParams {
        val aspect = PipState.videoAspect.coerceIn(1 / 2.39, 2.39)
        val ratio = Rational((aspect * 1000).roundToInt(), 1000)
        return PictureInPictureParams.Builder().apply {
            setAspectRatio(ratio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(PipState.wanted)
        }.build()
    }

    /** Battery saver is the phone's low-power state: glass falls back to flat while it is on (PowerStatus). */
    private fun watchPowerSave() {
        val power = getSystemService(PowerManager::class.java)
        PowerStatus.lowBattery = power.isPowerSaveMode
        if (powerReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                PowerStatus.lowBattery = power.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerReceiver = receiver
    }

    private fun unwatchPowerSave() {
        powerReceiver?.let { unregisterReceiver(it) }
        powerReceiver = null
    }

    /** Heat counts as saving too: from moderate heat on, glass goes flat and P2P uploads less (PowerStatus.saving). Android 10 and later. */
    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        val power = getSystemService(PowerManager::class.java)
        // The listener only reports changes, so the status at the start is read here: a change while the app was hidden is not missed.
        PowerStatus.thermalThrottled = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            PowerStatus.thermalThrottled = status >= PowerManager.THERMAL_STATUS_MODERATE
        }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
        thermalListener = listener
    }

    private fun unwatchThermal() {
        val listener = thermalListener ?: return
        getSystemService(PowerManager::class.java).removeThermalStatusListener(listener)
        thermalListener = null
    }

    /** Dev tools show only in debug builds. */
    private fun isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

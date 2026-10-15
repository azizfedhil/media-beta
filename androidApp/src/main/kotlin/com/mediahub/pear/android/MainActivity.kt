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
import android.os.PowerManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.mediahub.pear.App
import com.mediahub.pear.AndroidHost
import com.mediahub.pear.data.androidStorage
import com.mediahub.pear.glass.PowerStatus
import com.mediahub.pear.net.androidNetwork
import com.mediahub.pear.player.PipState
import com.mediahub.pear.shell.AppExit
import com.mediahub.pear.shell.AppLifecycle
import com.mediahub.pear.shell.WindowMode
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val storage by lazy { androidStorage(this) }
    private val network by lazy { androidNetwork() }
    private val control by lazy { AndroidWindowControl(this) }
    private var powerReceiver: BroadcastReceiver? = null

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
            Box(Modifier.fillMaxSize().then(safe)) {
                App(windowControl = control, devTools = isDebuggable(), storage = storage, network = network)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        watchPowerSave()
    }

    override fun onStop() {
        unwatchPowerSave()
        // A Picture in Picture window keeps the film going, so only an app that is really hidden counts as left.
        if (!isInPictureInPictureMode) AppLifecycle.notifyBackgrounded()
        storage.flush()
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

    /** Dev tools show only in debug builds. */
    private fun isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

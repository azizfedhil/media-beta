package com.mediahub.pear.player

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.mediahub.pear.AndroidHost

/**
 * Android: the window's keep-screen-on flag, set on the main thread while the player wants the screen awake. With no
 * activity on screen the call does nothing.
 */
private object WindowKeepAwake : KeepAwake {
    private val main = Handler(Looper.getMainLooper())

    override fun setAwake(awake: Boolean) {
        main.post {
            val window = AndroidHost.activity?.get()?.window ?: return@post
            if (awake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun release() = setAwake(false)
}

actual fun createKeepAwake(): KeepAwake = WindowKeepAwake

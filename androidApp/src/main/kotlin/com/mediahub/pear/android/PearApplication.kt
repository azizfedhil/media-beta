package com.mediahub.pear.android

import android.app.Application
import com.mediahub.pear.AndroidHost
import java.io.File

class PearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidHost.attach(this)
        installCaBundle()
    }

    /**
     * Copies the bundled CA roots (assets/cacert.pem) into the app's files folder when they are missing or have changed.
     * The player reads them there: without them, https sources cannot be verified (decisions.md, D15).
     */
    private fun installCaBundle() {
        runCatching {
            val target = File(filesDir, "cacert.pem")
            val bundled = assets.open("cacert.pem").use { it.readBytes() }
            if (!target.isFile || !target.readBytes().contentEquals(bundled)) target.writeBytes(bundled)
        }
    }
}

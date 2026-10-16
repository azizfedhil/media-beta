package com.mediahub.pear.player

import com.mediahub.pear.AndroidHost
import java.io.File

actual fun createDefaultPlayerEngine(): PlayerEngine {
    val app = AndroidHost.app()
    // CA bundle for https sources. The app copies it into filesDir at start (see docs/android/decisions.md, D15).
    val caCert = File(app.filesDir, "cacert.pem").takeIf { it.isFile }
    return AndroidPlayers.create(app, caCert)
}

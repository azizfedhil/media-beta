package com.mediahub.pear.player

import android.content.Context
import com.mediahub.pear.player.mpv.AndroidMpvEngine
import java.io.File

/**
 * Which engine plays video on Android. libmpv ships. Media3 is kept as a named option so it can be built later without
 * touching the player UI: add its engine class, one branch in [create], and one branch in VideoSurface.android.kt.
 */
enum class AndroidPlayerBackend { LibMpv, Media3 }

object AndroidPlayers {
    /** The backend the app uses. Changing it is the only switch needed once another backend exists. */
    val backend: AndroidPlayerBackend = AndroidPlayerBackend.LibMpv

    /** Starts the engine. Never throws: a failure becomes a [DummyPlayerEngine] whose message the player shows. */
    fun create(context: Context, caCertFile: File?): PlayerEngine = when (backend) {
        AndroidPlayerBackend.LibMpv -> try {
            AndroidMpvEngine.create(context, caCertFile)
        } catch (e: Throwable) {
            DummyPlayerEngine("libmpv could not start: ${e.message ?: e.javaClass.simpleName}")
        }
        AndroidPlayerBackend.Media3 -> DummyPlayerEngine("The Media3 engine is not built in this version.")
    }
}

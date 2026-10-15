package com.mediahub.pear.shell

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app leaving the screen (E3). Android sends [notifyBackgrounded] when the activity stops, and only when no Picture in
 * Picture window keeps it on screen. Desktop never sends it. The player pauses on each event. Mirrors [AppExit].
 */
object AppLifecycle {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    val backgrounded: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyBackgrounded() {
        events.tryEmit(Unit)
    }
}

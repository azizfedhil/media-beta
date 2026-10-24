package com.mediahub.pear.shell

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The app leaving the screen (E3). Android sends [notifyBackgrounded] when the activity stops, and only when no Picture in
 * Picture window keeps it on screen. Desktop never sends it. The player pauses on each event. Mirrors [AppExit].
 */
object AppLifecycle {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    val backgrounded: SharedFlow<Unit> = events.asSharedFlow()

    private val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The app coming to the front (Android: the activity starts again). The Pear.pair engine syncs on it. */
    val foregrounded: SharedFlow<Unit> = resumes.asSharedFlow()

    private val foreground = MutableStateFlow(true)

    /** True while the app is in front. Desktop never sends [notifyBackgrounded], so it stays true there. */
    val isForeground: StateFlow<Boolean> = foreground.asStateFlow()

    fun notifyForegrounded() {
        foreground.value = true
        resumes.tryEmit(Unit)
    }

    fun notifyBackgrounded() {
        foreground.value = false
        events.tryEmit(Unit)
    }

    /** Suspends until the app is in front. Returns at once when it already is. Timers use this to stay quiet while hidden. */
    suspend fun awaitForeground() {
        isForeground.first { it }
    }
}

package com.mediahub.pear.glass

import com.mediahub.pear.epochMillis
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile

/**
 * Decides from the time of each drawn frame whether the UI is too slow. Pure: no platform calls. Too slow means the average
 * of [windowFrames] frames is under [slowFps], or [hangsToTrigger] frames took [hangNanos] or more (a hang).
 */
class FrameWatch(
    private val windowFrames: Int = 90,
    private val slowFps: Double = 55.0,
    private val hangNanos: Long = 700_000_000L,
    private val hangsToTrigger: Int = 2,
) {
    private var count = 0
    private var totalNanos = 0L
    private var hangs = 0

    /** Feeds one drawn frame. True once when the window shows the UI is too slow; a new window starts after that. */
    fun add(frameNanos: Long): Boolean {
        if (frameNanos <= 0L) return false
        count++
        totalNanos += frameNanos
        if (frameNanos >= hangNanos) hangs++
        if (hangs >= hangsToTrigger) {
            reset()
            return true
        }
        if (count < windowFrames) return false
        val fps = 1_000_000_000.0 * count / totalNanos
        reset()
        return fps < slowFps
    }

    fun reset() {
        count = 0
        totalNanos = 0L
        hangs = 0
    }
}

/** The app's frame monitor. The platform feeds [frame]; the app listens to [slow] and rests the monitor after a change. */
object FramePacing {
    private val watch = FrameWatch()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the UI has been too slow. */
    val slow: SharedFlow<Unit> = events.asSharedFlow()

    /** False while the player is open or the setup shows: nothing is judged then. */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var ignoreUntil: Long = 0L

    /** Called for every drawn frame, from any thread. */
    fun frame(frameNanos: Long) {
        if (!enabled || epochMillis() < ignoreUntil) return
        val tooSlow = synchronized(watch) { watch.add(frameNanos) }
        if (tooSlow) events.tryEmit(Unit)
    }

    /** Judges nothing for [millis] and starts a new window (after launch, a change of tier, or leaving the player). */
    fun rest(millis: Long) {
        ignoreUntil = epochMillis() + millis
        synchronized(watch) { watch.reset() }
    }
}

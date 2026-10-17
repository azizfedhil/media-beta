package com.mediahub.pear.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The flicker trace, for code in commonMain. Off on every launch. While it is on, each call adds a line, a count or a
 * sample to the trace file through [sink]. While it is off, each call is one read of [enabled] and returns.
 *
 * Nothing here throws: every call is guarded. The sink only enqueues, so a call never waits for the disk. Android has no
 * sink, so [start] turns nothing on there.
 */
object PearTrace {
    /** Set by the desktop at startup. Null on Android, where [start] does nothing. */
    var sink: TraceSink? = null

    private val _enabled = MutableStateFlow(false)

    /** Whether tracing is on. Settings and the UI probes collect it. */
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The hot-path check. Reading it allocates nothing. */
    val enabled: Boolean get() = _enabled.value

    private val toggleLock = Any()

    /** Starts tracing. It turns on only if the sink started, so a logs folder that cannot be written leaves it off. */
    fun start() {
        synchronized(toggleLock) {
            if (_enabled.value) return
            val started = runCatching { sink?.start() == true }.getOrDefault(false)
            if (started) _enabled.value = true
        }
    }

    /**
     * Stops tracing. The flag goes off first, so nothing more is queued, and then the sink writes its last lines and
     * closes the file. This waits for the file to close (a few seconds at most), so call it off the UI thread.
     */
    fun stop() {
        synchronized(toggleLock) {
            _enabled.value = false
            runCatching { sink?.stop() }
        }
    }

    /** One timestamped line in category [cat]. [msg] is only built while tracing is on. */
    inline fun event(cat: String, msg: () -> String) {
        if (enabled) runCatching { sink?.event(cat, msg()) }
    }

    /** Adds [n] to the count for [key]. Counts are summed each second. */
    fun count(key: String, n: Long = 1L) {
        if (enabled) runCatching { sink?.count(key, n) }
    }

    /** One value for [key]. Each second the trace shows the count, minimum, average and maximum of its values. */
    fun sample(key: String, value: Double) {
        if (enabled) runCatching { sink?.sample(key, value) }
    }

    /** The user's "I saw it now" marker: a MARK line, the facts, and the last three STAT lines. */
    fun mark() {
        if (enabled) runCatching { sink?.mark() }
    }
}

/**
 * What the platform writes the trace with. Each method is called only while tracing is on, and none of them may block the
 * caller on the disk.
 */
interface TraceSink {
    /** Opens the file and starts its threads. False if it could not, and the trace then stays off. */
    fun start(): Boolean

    /** Writes the final lines and closes the file. */
    fun stop()

    fun event(cat: String, msg: String)

    fun count(key: String, n: Long)

    fun sample(key: String, value: Double)

    fun mark()
}

/** Hooks the platform fills in, so Settings (commonMain) can offer the buttons. Null means not available (Android). */
object Diagnostics {
    /** Opens the logs folder in the file manager. */
    var openLogsFolder: (() -> Unit)? = null

    /** Writes the diagnostics zip and returns its path, or null on failure. Call it off the UI thread. */
    var exportZip: (() -> String?)? = null
}

/**
 * Counts a recomposition of the composable it is called in (`rc.<name>`). Inline on purpose: a call that could be skipped
 * would skip the count with it. The `if` keeps it free while tracing is off.
 */
@Composable
inline fun TraceRecompose(name: String) {
    if (PearTrace.enabled) SideEffect { PearTrace.count("rc.$name") }
}

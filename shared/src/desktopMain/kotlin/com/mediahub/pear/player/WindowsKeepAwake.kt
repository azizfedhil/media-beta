package com.mediahub.pear.player

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows: SetThreadExecutionState keeps the display and the system awake (Batch 4.6c). The state belongs to the thread
 * that set it, so every call is made from the UI thread, which is the one that sets and clears it.
 */
internal class WindowsKeepAwake : KeepAwake {
    private var awake = false

    override fun setAwake(awake: Boolean) {
        if (awake == this.awake) return
        this.awake = awake
        // ES_CONTINUOUS alone clears what was requested before.
        val flags = if (awake) ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED else ES_CONTINUOUS
        Kernel32Power.INSTANCE.SetThreadExecutionState(flags)
    }

    override fun release() = setAwake(false)
}

// EXECUTION_STATE flags (winbase.h). ES_CONTINUOUS is 0x80000000, which doesn't fit in an Int literal.
private const val ES_CONTINUOUS = Int.MIN_VALUE
private const val ES_SYSTEM_REQUIRED = 0x00000001
private const val ES_DISPLAY_REQUIRED = 0x00000002

/** The one kernel32 call this file needs, bound by hand, as in PowerWatch. */
private interface Kernel32Power : StdCallLibrary {
    fun SetThreadExecutionState(flags: Int): Int

    companion object {
        val INSTANCE: Kernel32Power = Native.load("kernel32", Kernel32Power::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

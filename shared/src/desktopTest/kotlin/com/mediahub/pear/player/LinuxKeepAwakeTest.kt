package com.mediahub.pear.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxKeepAwakeTest {

    @Test
    fun theInhibitorHoldsIdleAndSleepAndWaitsOnItsPipe() {
        val command = LinuxKeepAwake.command()
        assertEquals("systemd-inhibit", command.first())
        assertTrue("--what=idle:sleep" in command, "screen blanking, the lock and sleep")
        assertTrue("--mode=block" in command)
        assertEquals("cat", command.last(), "cat ends when Pear closes the pipe, which releases the inhibitor")
    }
}

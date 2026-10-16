package com.mediahub.pear.player.mpv

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvUtf8Test {

    /** A NUL-terminated C string holding [bytes], as mpv hands one back. */
    private fun cString(bytes: ByteArray): Memory {
        val mem = Memory(bytes.size + 1L)
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)
        return mem
    }

    @Test
    fun arabicSubtitleLineDecodesFromUtf8() {
        // U+202B and U+202C are the bidi marks mpv wraps RTL text in.
        val line = "\u202bأسئلة\u202c"
        assertEquals(line, mpvUtf8(cString(line.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun nullPointerGivesNull() {
        assertNull(mpvUtf8(null))
    }
}

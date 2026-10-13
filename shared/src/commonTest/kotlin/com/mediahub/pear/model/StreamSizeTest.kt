package com.mediahub.pear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamSizeTest {
    @Test
    fun parsesTheSizeAnAddonNames() {
        assertEquals(1_400_000_000L, StreamSize.parse("💾 1.4 GB · Torrentio"))
        assertEquals(850_000_000L, StreamSize.parse("850 MB"))
        assertEquals(1_500L, StreamSize.parse("1,5 KB"))
    }

    @Test
    fun noSizeMeansNull() {
        assertNull(StreamSize.parse("1080p WEB-DL x264"))
        assertNull(StreamSize.parse(null))
        assertNull(StreamSize.parse("  "))
    }

    @Test
    fun formatsDecimalUnits() {
        assertEquals("2.3 GB", StreamSize.format(2_300_000_000L))
        assertEquals("850 MB", StreamSize.format(850_000_000L))
        assertEquals("1.5 KB", StreamSize.format(1_500L))
        assertEquals("512 B", StreamSize.format(512L))
    }
}

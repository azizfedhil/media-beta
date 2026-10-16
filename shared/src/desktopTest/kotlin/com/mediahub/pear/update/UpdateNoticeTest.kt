package com.mediahub.pear.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateNoticeTest {
    @Test
    fun manifestUrlIsBuiltFromTheRepo() {
        assertEquals(
            "https://github.com/azizfedhil/media-beta/releases/latest/download/latest.json",
            latestManifestUrl("azizfedhil/media-beta"),
        )
        assertEquals(
            "https://github.com/a/b/releases/latest/download/latest.json",
            latestManifestUrl(" /a/b/ "),
        )
    }

    @Test
    fun noRepoMeansNoCheck() {
        assertNull(latestManifestUrl(""))
        assertNull(latestManifestUrl("  "))
    }

    @Test
    fun platformKeysMatchTheManifest() {
        assertEquals("windows-x64", updatePlatformKey("Windows 11"))
        assertEquals("linux-x64-rpm", updatePlatformKey("Linux"))
        assertNull(updatePlatformKey("Mac OS X"))
    }
}
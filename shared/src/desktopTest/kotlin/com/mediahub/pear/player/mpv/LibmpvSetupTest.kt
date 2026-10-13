package com.mediahub.pear.player.mpv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibmpvSetupTest {
    private val localAppData: (String) -> String? = { if (it == "LOCALAPPDATA") "C:/Users/a/AppData/Local" else null }

    @Test
    fun anUnpinnedBuildNeverFetches() {
        if (!LibmpvSetup.isPinned) assertFalse(LibmpvSetup.needsFetch(localAppData))
    }

    @Test
    fun aDeveloperOverrideMeansTheChoiceIsTheirs() {
        val withOverride: (String) -> String? = { if (it == "PEAR_LIBMPV") "C:/dev/libmpv-2.dll" else localAppData(it) }
        assertFalse(LibmpvSetup.needsFetch(withOverride))
    }

    @Test
    fun theTargetIsTheNativeFolderOnlyOnWindows() {
        val target = LibmpvSetup.target(localAppData)
        if (MpvNativeLoader.Platform.current == MpvNativeLoader.Platform.Windows) {
            assertEquals("libmpv-2.dll", target?.name)
            assertEquals(File(File("C:/Users/a/AppData/Local", "Pear"), "native"), target?.parentFile)
        } else {
            assertEquals(null, target)
        }
    }

    @Test
    fun theDriveLinkComesFromTheBuildAndIsTheDownloadForm() {
        assertTrue(LibmpvSetup.DRIVE_URL.startsWith("https://drive.usercontent.google.com/download?id="), LibmpvSetup.DRIVE_URL)
        assertTrue(LibmpvSetup.DRIVE_URL.contains("export=download"), LibmpvSetup.DRIVE_URL)
    }

    @Test
    fun theSourcesAreOnlyHttpsAddresses() {
        assertTrue(LibmpvSetup.sources.all { it.startsWith("https://") })
        assertTrue(LibmpvSetup.sources.isNotEmpty())
    }
}

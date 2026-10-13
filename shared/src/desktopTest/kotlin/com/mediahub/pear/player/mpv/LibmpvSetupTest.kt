package com.mediahub.pear.player.mpv

import com.mediahub.pear.net.isSha256Hex
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
    fun theDriveLinkIsTheOneWeDocument() {
        assertTrue(LibmpvSetup.DRIVE_URL.startsWith("https://drive.usercontent.google.com/download?id=1zF1TbPHoSBVTRzjPVX7ktmwWb6rAUVM4"))
        assertTrue(LibmpvSetup.DRIVE_URL.contains("export=download"))
    }

    @Test
    fun theKotlinPinAndTheInstallerPinAgreeOnceBothExist() {
        // Tests run in shared/, so the installer's pin file is one folder up. Until it exists there is nothing to agree with.
        val pinFile = File("../desktopApp/installer/libmpv.pin.iss")
        if (!pinFile.isFile) return
        val installer = Regex("#define\\s+LibmpvSha256\\s+\"([^\"]*)\"").find(pinFile.readText())?.groupValues?.get(1)
        assertEquals(installer, LibmpvSetup.SHA256, "desktopApp/installer/libmpv.pin.iss and LibmpvSetup.SHA256 must match")
        assertTrue(isSha256Hex(LibmpvSetup.SHA256), "the pin must be a real SHA-256 once the installer has one")
    }
}

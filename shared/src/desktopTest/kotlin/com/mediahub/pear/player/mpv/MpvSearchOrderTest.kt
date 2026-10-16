package com.mediahub.pear.player.mpv

import com.mediahub.pear.player.mpv.MpvNativeLoader.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvSearchOrderTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private val installedApp = "C:/Program Files/Pear/app/resources"
    private val userDir = "C:/src/pear"

    private fun windowsDirs(vararg extra: Pair<String, String>): List<File> = MpvNativeLoader.searchDirectories(
        Platform.Windows,
        env = env("LOCALAPPDATA" to "C:/Users/a/AppData/Local", *extra),
        property = mapOf("compose.application.resources.dir" to installedApp, "user.dir" to userDir)::get,
    )

    @Test
    fun windowsNativeFolderComesAfterTheInstalledAppAndBeforeTheSourceFolders() {
        val dirs = windowsDirs()
        val installed = dirs.indexOf(File(installedApp))
        val native = dirs.indexOf(File(File("C:/Users/a/AppData/Local", "Pear"), "native"))
        val source = dirs.indexOf(File(userDir, "desktopApp/packaging/resources/windows-x64"))

        assertTrue(installed >= 0, "installed app folder is searched: $dirs")
        assertTrue(native > installed, "native folder is searched after the installed app: $dirs")
        assertTrue(source > native, "source-run folders are searched after the native folder: $dirs")
    }

    @Test
    fun devOverrideStillComesFirst() {
        val dirs = windowsDirs("PEAR_LIBMPV_DIR" to "D:/mpv")
        assertEquals(File("D:/mpv"), dirs.first())
    }

    @Test
    fun windowsWithoutLocalAppDataSkipsTheNativeFolder() {
        val dirs = MpvNativeLoader.searchDirectories(
            Platform.Windows,
            env = env(),
            property = mapOf("user.dir" to userDir)::get,
        )
        assertFalse(dirs.any { it.path.endsWith(File.separator + "native") && it.path.contains("Pear") })
    }

    @Test
    fun linuxNeverSearchesTheWindowsNativeFolder() {
        val dirs = MpvNativeLoader.searchDirectories(
            Platform.Linux,
            env = env("LOCALAPPDATA" to "C:/Users/a/AppData/Local"),
            property = mapOf("user.dir" to "/home/a/pear")::get,
        )
        assertFalse(dirs.any { it.path.contains("AppData") })
    }

    @Test
    fun windowsNativeFolderIsPearNativeUnderLocalAppData() {
        assertEquals(
            File(File("C:/Users/a/AppData/Local", "Pear"), "native"),
            MpvNativeLoader.windowsNativeFolder(env("LOCALAPPDATA" to "C:/Users/a/AppData/Local")),
        )
        assertEquals(null, MpvNativeLoader.windowsNativeFolder(env()))
    }

    @Test
    fun linuxAsksForTheVersionedSonameFirst() {
        assertEquals("libmpv.so.2", MpvNativeLoader.systemNames(Platform.Linux).first())
        assertTrue("libmpv" in MpvNativeLoader.systemNames(Platform.Linux), "the unversioned name is still tried")
    }

    @Test
    fun windowsSystemNamesAreUnchanged() {
        val names = MpvNativeLoader.systemNames(Platform.Windows)
        assertEquals(listOf("mpv-2", "libmpv-2", "mpv-1", "libmpv-1", "mpv", "libmpv"), names)
    }
}

package com.mediahub.pear.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun windowsUsesAppData() {
        val d = AppDirs.resolve("Windows 11", env("APPDATA" to "C:/Users/a/AppData/Roaming", "LOCALAPPDATA" to "C:/Users/a/AppData/Local"), "C:/Users/a")
        assertEquals(File("C:/Users/a/AppData/Roaming/Pear"), d.config)
        assertEquals(File("C:/Users/a/AppData/Local/Pear/Cache"), d.cache)
    }

    @Test
    fun windowsWithoutVariablesFallsBackToHome() {
        val d = AppDirs.resolve("Windows 10", env(), "C:/Users/a")
        assertEquals(File("C:/Users/a/AppData/Roaming/Pear"), d.config)
    }

    @Test
    fun linuxFollowsXdg() {
        val d = AppDirs.resolve("Linux", env("XDG_CONFIG_HOME" to "/x/cfg", "XDG_CACHE_HOME" to ""), "/home/a")
        assertEquals(File("/x/cfg/pear"), d.config)
        assertEquals(File("/home/a/.cache/pear"), d.cache) // blank means unset
        assertEquals(File("/home/a/.config/pear"), AppDirs.resolve("Linux", env(), "/home/a").config)
    }

    @Test
    fun macOsIsNotMistakenForWindows() {
        // "Mac OS X" and "Darwin" both contain letters that spell "win" in other words; check they go to Library.
        assertEquals(File("/Users/a/Library/Application Support/Pear"), AppDirs.resolve("Mac OS X", env(), "/Users/a").config)
        assertEquals(File("/Users/a/Library/Application Support/Pear"), AppDirs.resolve("Darwin", env(), "/Users/a").config)
    }
}

package com.mediahub.pear.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSecretStoreTest {
    private class FakeKeyring(var works: Boolean = true) : Keyring {
        override val name = "fake keyring"
        val items = mutableMapOf<String, String>()
        override fun store(key: String, value: String): Boolean { if (works) items[key] = value; return works }
        override fun lookup(key: String) = if (works) items[key] else null
        override fun clear(key: String) { items.remove(key) }
    }

    /** Reverses the text: not encryption, but proves the file holds something other than the secret. */
    private class FakeProtector(var works: Boolean = true) : Protector {
        override val name = "fake dpapi"
        override fun protect(data: ByteArray) = if (works) data.reversedArray() else null
        override fun unprotect(data: ByteArray) = if (works) data.reversedArray() else null
    }

    private fun file(): File = File(Files.createTempDirectory("pear-secrets").toFile().also { it.deleteOnExit() }, "secrets.json")

    @Test
    fun keyringComesFirstAndLeavesNothingInTheFile() {
        val f = file(); val kr = FakeKeyring()
        val s = DesktopSecretStore(f, kr, null)
        assertTrue(s.set("simkl.token", "T"))
        assertEquals("T", s.get("simkl.token"))
        assertFalse(f.exists() && f.readText().contains("\"T\""))
    }

    @Test
    fun aRefusingKeyringFallsBackToTheFileAndReportsIt() {
        val f = file(); val kr = FakeKeyring(works = false)
        val s = DesktopSecretStore(f, kr, null)
        assertFalse(s.set("a", "plain-secret"))
        assertEquals("plain-secret", s.get("a"))
        assertEquals("plain-secret", DesktopSecretStore(f, null, null).get("a")) // survives a restart
    }

    @Test
    fun dpapiEntriesAreNotPlainInTheFile() {
        val f = file()
        val s = DesktopSecretStore(f, null, FakeProtector())
        assertTrue(s.set("a", "hello"))
        assertFalse(f.readText().contains("hello"))
        assertEquals("hello", DesktopSecretStore(f, null, FakeProtector()).get("a"))
    }

    @Test
    fun aFailingProtectorFallsBackToPlain() {
        val f = file()
        assertFalse(DesktopSecretStore(f, null, FakeProtector(works = false)).set("a", "v"))
        assertEquals("v", DesktopSecretStore(f, null, null).get("a"))
    }

    @Test
    fun movingToTheKeyringDropsTheFileCopy() {
        val f = file(); val kr = FakeKeyring(works = false)
        val s = DesktopSecretStore(f, kr, null)
        s.set("a", "old")
        kr.works = true
        assertTrue(s.set("a", "new"))
        assertEquals("new", s.get("a"))
        kr.works = false // keyring gone: nothing stale may come back from the file
        assertNull(s.get("a"))
    }

    @Test
    fun removeClearsEverywhere() {
        val f = file(); val kr = FakeKeyring()
        val s = DesktopSecretStore(f, kr, FakeProtector())
        s.set("a", "v")
        s.remove("a")
        assertNull(s.get("a"))
        assertTrue(kr.items.isEmpty())
        assertNull(s.get("never-set"))
    }
}

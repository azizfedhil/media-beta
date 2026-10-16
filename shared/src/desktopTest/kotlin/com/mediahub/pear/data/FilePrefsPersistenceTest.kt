package com.mediahub.pear.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePrefsPersistenceTest {
    private fun tempDir(): File = Files.createTempDirectory("pear-test").toFile().also { it.deleteOnExit() }

    private fun store(file: File) = PrefStore(FilePrefsPersistence(file, debounceMillis = 20, registerShutdownHook = false))

    @Test
    fun valuesSurviveARestart() {
        val file = File(tempDir(), "settings.json")
        val a = store(file)
        a.setString("tmdb.key", "abc")
        a.setBool("skip.enabled", false)
        a.setJsonText("profiles.list", """[{"id":"default"}]""")
        a.flush()
        val b = store(file)
        assertEquals("abc", b.string("tmdb.key"))
        assertEquals(false, b.bool("skip.enabled"))
        assertEquals("""[{"id":"default"}]""", b.jsonText("profiles.list"))
    }

    @Test
    fun savesHappenInTheBackgroundWithoutFlush() {
        val file = File(tempDir(), "settings.json")
        store(file).setInt("n", 5)
        repeat(100) { if (!file.isFile) Thread.sleep(20) }
        assertTrue(file.isFile, "debounced write never happened")
        assertEquals(5, store(file).int("n"))
    }

    @Test
    fun aBurstOfChangesIsOneFileWithTheLastValues() {
        val file = File(tempDir(), "nested/dir/settings.json") // folders are created
        val a = store(file)
        repeat(200) { a.setInt("n", it) }
        a.flush()
        assertEquals(199, store(file).int("n"))
        assertTrue(!File(file.path + ".tmp").exists())
    }

    @Test
    fun aDamagedFileIsKeptAndTheStoreStartsEmpty() {
        val file = File(tempDir(), "settings.json")
        file.writeText("{ this is not json")
        val s = store(file)
        assertTrue(s.keys.isEmpty())
        assertTrue(File(file.path + ".bad").isFile)
        s.setString("a", "1")
        s.flush()
        assertEquals("1", store(file).string("a"))
    }
}

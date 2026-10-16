package com.mediahub.pear.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefStoreTest {
    private class Memory : PrefsPersistence {
        var stored: Map<String, PrefValue> = emptyMap()
        var saves = 0
        override fun load() = stored
        override fun save(values: Map<String, PrefValue>) { stored = values; saves++ }
    }

    @Test
    fun codecRoundTripsEveryKind() {
        val values = mapOf(
            "s" to PrefValue.Text("1"),
            "b" to PrefValue.Flag(true),
            "i" to PrefValue.Whole(42),
            "d" to PrefValue.Real(1.5),
            "x" to PrefValue.Blob(byteArrayOf(0, 1, 2, 127, -1)),
            "l" to PrefValue.TextList(listOf("a", "b")),
            "m" to PrefValue.Dict(mapOf("k" to PrefValue.Text("v"), "n" to PrefValue.Whole(3))),
            "p" to PrefValue.Moment(1_700_000_000_000),
        )
        val back = PrefsCodec.decode(PrefsCodec.encode(values))
        assertEquals(values, back)
    }

    @Test
    fun aStringOneIsNotANumberOne() {
        val back = PrefsCodec.decode(PrefsCodec.encode(mapOf("a" to PrefValue.Text("1"), "b" to PrefValue.Whole(1))))!!
        assertEquals(PrefValue.Text("1"), back["a"])
        assertEquals(PrefValue.Whole(1), back["b"])
    }

    @Test
    fun decodeSkipsDamagedEntriesAndRejectsNonFiles() {
        val text = """{"format":1,"values":{"ok":{"t":"s","v":"x"},"bad":{"t":"i","v":"nope"},"odd":5,"new":{"t":"zz","v":1}}}"""
        assertEquals(mapOf<String, PrefValue>("ok" to PrefValue.Text("x")), PrefsCodec.decode(text))
        assertNull(PrefsCodec.decode("not json"))
        assertNull(PrefsCodec.decode("""{"something":"else"}"""))
    }

    @Test
    fun typedReadsAndDefaults() {
        val p = PrefStore()
        assertEquals("d", p.string("k", "d"))
        p.setString("k", "v"); p.setBool("b", true); p.setInt("i", 7); p.setDouble("d", 2.5)
        p.setStringList("l", listOf("x"))
        assertEquals("v", p.string("k"))
        assertEquals(true, p.bool("b"))
        assertEquals(7, p.int("i"))
        assertEquals(7.0, p.double("i"))
        assertEquals(2.5, p.double("d"))
        assertEquals(listOf("x"), p.stringList("l"))
        assertNull(p.bool("k")) // wrong type reads as missing
        p.remove("k")
        assertTrue("k" !in p)
    }

    @Test
    fun jsonIsStoredAsBytesLikeIosAndReadBackAsText() {
        val p = PrefStore()
        p.setJsonText("j", """{"a":1}""")
        assertTrue(p["j"] is PrefValue.Blob)
        assertEquals("""{"a":1}""", p.jsonText("j"))
        p.setString("t", "{}")
        assertEquals("{}", p.jsonText("t")) // text is accepted too
    }

    @Test
    fun changesAreSavedAndAnnounced() {
        val mem = Memory()
        val p = PrefStore(mem)
        val seen = mutableListOf<Set<String>>()
        val stop = p.listen { seen += it }
        p.setString("a", "1")
        p.setString("a", "1") // same value: nothing happens
        p.putAll(mapOf("b" to PrefValue.Flag(true), "c" to PrefValue.Whole(1)))
        assertEquals(2, mem.saves)
        assertEquals(listOf(setOf("a"), setOf("b", "c")), seen)
        stop()
        p.remove("a")
        assertEquals(2, seen.size)
        assertTrue("a" !in mem.stored)
    }

    @Test
    fun loadsWhatPersistenceHolds() {
        val mem = Memory().apply { stored = mapOf("ui.accent" to PrefValue.Text("#22C55E")) }
        assertEquals("#22C55E", PrefStore(mem).string(PrefKeys.UI_ACCENT))
    }

    @Test
    fun blobIsCopiedAndComparedByContent() {
        val raw = byteArrayOf(1, 2, 3)
        val b = PrefValue.Blob(raw)
        raw[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), b.toByteArray())
        assertEquals(PrefValue.Blob(byteArrayOf(1, 2, 3)), b)
        assertNotNull(b.hashCode())
    }
}

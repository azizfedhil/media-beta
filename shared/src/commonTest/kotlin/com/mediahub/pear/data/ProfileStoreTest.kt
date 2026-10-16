package com.mediahub.pear.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStoreTest {
    private fun store(prefs: PrefStore = PrefStore()): Pair<PrefStore, ProfileStore> {
        var n = 0
        return prefs to ProfileStore(prefs, newId = { "id${++n}" })
    }

    @Test
    fun startsWithTheDefaultProfile() {
        val (_, s) = store()
        assertEquals(1, s.profiles.size)
        assertEquals(ProfileKeys.DEFAULT_ID, s.activeID)
        assertEquals("Me", s.active.name)
    }

    @Test
    fun addSelectAndPersist() {
        val (prefs, s) = store()
        val kid = assertNotNull(s.add("  Kids  ", "#FF0000", "star"))
        assertEquals("Kids", kid.name)
        s.select(kid.id)
        assertEquals(kid.id, prefs.string(PrefKeys.PROFILES_ACTIVE))
        val again = ProfileStore(prefs)
        assertEquals(listOf("default", kid.id), again.profiles.map { it.id })
        assertEquals(kid.id, again.activeID)
        assertEquals("star", again.active.symbol)
    }

    @Test
    fun namesAreCleaned() {
        val (_, s) = store()
        assertEquals("Profile", s.add("   ", "#000000", "")!!.name)
        assertEquals(20, s.add("x".repeat(40), "#000000", "")!!.name.length)
    }

    @Test
    fun limitIsEight() {
        val (_, s) = store()
        repeat(7) { assertNotNull(s.add("P$it", "#000000", "")) }
        assertTrue(!s.canAdd)
        assertNull(s.add("Nine", "#000000", ""))
    }

    @Test
    fun eachProfileHasItsOwnKeys() {
        val (prefs, s) = store()
        val p = s.add("B", "#000000", "")!!
        prefs.setString(ProfileKeys.scoped("watch.history", ProfileKeys.DEFAULT_ID), "a")
        prefs.setString(ProfileKeys.scoped("watch.history", p.id), "b")
        assertEquals("a", prefs.string("watch.history"))
        assertEquals("b", prefs.string("watch.history.${p.id}"))
    }

    @Test
    fun deleteWipesTheProfilesDataAndKeepsTheLastOne() {
        val (prefs, s) = store()
        val p = s.add("B", "#000000", "")!!
        for (base in ProfileKeys.scopedBases) prefs.setString(ProfileKeys.scoped(base, p.id), "x")
        prefs.setString("watch.history", "mine")
        s.select(p.id)
        s.delete(p.id)
        assertEquals(ProfileKeys.DEFAULT_ID, s.activeID)
        for (base in ProfileKeys.scopedBases) assertNull(prefs.string(ProfileKeys.scoped(base, p.id)))
        assertEquals("mine", prefs.string("watch.history"))
        s.delete(ProfileKeys.DEFAULT_ID) // the last one stays
        assertEquals(1, s.profiles.size)
    }

    @Test
    fun readsTheJsonTheSwiftAppWrites() {
        val prefs = PrefStore()
        prefs.setJsonText(PrefKeys.PROFILES_LIST, """[{"id":"default","name":"Sam","colorHex":"#7D5CFF","symbol":""},{"id":"A-B","name":"Kid","colorHex":"#FF0000","symbol":"star","simklIdentity":true}]""")
        prefs.setString(PrefKeys.PROFILES_ACTIVE, "A-B")
        val s = ProfileStore(prefs)
        assertEquals(listOf("Sam", "Kid"), s.profiles.map { it.name })
        assertEquals("A-B", s.activeID)
        assertEquals(true, s.active.simklIdentity)
        assertEquals("K", s.active.initial)
    }

    @Test
    fun anUnknownActiveIdFallsBackToTheFirstProfile() {
        val prefs = PrefStore()
        prefs.setString(PrefKeys.PROFILES_ACTIVE, "gone")
        assertEquals("default", ProfileStore(prefs).activeID)
    }

    @Test
    fun randomIdsLookLikeUuids() {
        val id = randomProfileId()
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}").matches(id), id)
    }
}

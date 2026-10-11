package com.mediahub.pear.addons

import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinnedSourcesTest {
    @Test
    fun aPinIsSavedAsTheJsonTheIosAppWrites() {
        val prefs = PrefStore()
        val pins = PinnedSources(prefs)
        pins.set(Pin("https://a.example/manifest.json", "Pengu 1080p"), "tt1")
        assertEquals(Pin("https://a.example/manifest.json", "Pengu 1080p"), pins.pin("tt1"))
        assertEquals("""{"tt1":{"addonID":"https://a.example/manifest.json","signature":"Pengu 1080p"}}""", prefs.jsonText(PrefKeys.PINNED_SOURCES))
    }

    @Test
    fun pinsSurviveANewInstanceAndReloadFollowsTheSettings() {
        val prefs = PrefStore()
        PinnedSources(prefs).set(Pin("a", "s"), "tt1")
        val again = PinnedSources(prefs)
        assertEquals("s", again.pin("tt1")?.signature)
        prefs.setJsonText(PrefKeys.PINNED_SOURCES, """{"tt9":{"addonID":"b","signature":"t"}}""")
        again.reload()
        assertNull(again.pin("tt1"))
        assertEquals("t", again.pin("tt9")?.signature)
    }

    @Test
    fun removeAndReplace() {
        val pins = PinnedSources(PrefStore())
        pins.set(Pin("a", "one"), "tt1")
        pins.set(Pin("a", "two"), "tt1")
        assertEquals("two", pins.pin("tt1")?.signature)
        pins.remove("tt1")
        assertNull(pins.pin("tt1"))
        pins.remove("tt1") // nothing to remove: fine
        assertTrue(pins.pins.isEmpty())
    }

    @Test
    fun unreadableSavedDataMeansNoPins() {
        val prefs = PrefStore()
        prefs.setJsonText(PrefKeys.PINNED_SOURCES, "not json")
        assertTrue(PinnedSources(prefs).pins.isEmpty())
    }
}

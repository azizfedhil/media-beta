package com.mediahub.pear.history

import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun m(id: String, name: String = id) = MetaPreview(id, "movie", name)

class LocalLibraryTest {
    private var t = 1_000L
    private fun lib(prefs: PrefStore = PrefStore()) = LocalLibrary(prefs, now = { t })

    @Test
    fun savingAddsToTheFrontAndChangingStatusMovesItThere() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        t = 2_000
        l.set(m("tt2"), LibraryStatus.Watched)
        assertEquals(listOf("tt2", "tt1"), l.entries.map { it.id })
        t = 3_000
        l.set(m("tt1"), LibraryStatus.Watched)
        assertEquals(listOf("tt1", "tt2"), l.entries.map { it.id })
        assertEquals(LibraryStatus.Watched, l.entry("tt1")!!.status)
        assertEquals(3_000L, l.entry("tt1")!!.added)
    }

    @Test
    fun anAliasMakesTheTitleFindableUnderItsOtherId() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        l.setAlias("tmdb:1", "tt1")
        assertEquals("tt1", l.entry("tmdb:1")?.id)
        l.setAlias("tmdb:2", "tt1") // already has one
        assertEquals("tmdb:1", l.entry("tt1")!!.alias)
        l.set(m("tmdb:1"), LibraryStatus.Watched) // saving under the other id updates the same entry
        assertEquals(1, l.entries.size)
    }

    @Test
    fun applyKeepsTheOrderGivenAndOnlyMovesExistingTitlesWhoseStatusChanged() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        l.set(m("tt2"), LibraryStatus.PlanToWatch)
        t = 10_000
        l.apply(listOf(m("tt2") to LibraryStatus.PlanToWatch, m("tt1") to LibraryStatus.Watched, m("tt3") to LibraryStatus.PlanToWatch, m("tt4") to LibraryStatus.Watched))
        assertEquals(listOf("tt3", "tt4", "tt2", "tt1"), l.entries.map { it.id })
        assertEquals(8_000L, l.entry("tt3")!!.added) // change number 2: base - 2 s
        assertEquals(7_000L, l.entry("tt4")!!.added)
        assertEquals(LibraryStatus.Watched, l.entry("tt1")!!.status)
        assertEquals(9_000L, l.entry("tt1")!!.added)
        assertEquals(1_000L, l.entry("tt2")!!.added) // unchanged status: not touched
    }

    @Test
    fun removingAndUnsavingDropTheEntry() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        l.setAlias("tmdb:1", "tt1")
        l.remove("tmdb:1")
        assertTrue(l.entries.isEmpty())
    }

    @Test
    fun aFinishedMovieOnlyUpdatesATitleThatWasAlreadySaved() {
        val l = lib()
        l.markWatchedIfSaved("tt1")
        assertTrue(l.entries.isEmpty())
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        l.markWatchedIfSaved("tt1")
        assertEquals(LibraryStatus.Watched, l.entry("tt1")!!.status)
    }

    @Test
    fun theRowsAreWatchingPlanToWatchAndWatchedWithoutEmptyOnes() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        l.set(m("tt2"), LibraryStatus.Watched)
        val rows = l.rows(listOf(m("w1"), m("tt2"))) // tt2 is watched, so it isn't "watching"
        assertEquals(listOf("Watching", "Plan to Watch", "Watched"), rows.map { it.title })
        assertEquals(listOf("w1"), rows[0].items.map { it.id })
        assertEquals(listOf("tt1"), rows[1].items.map { it.id })
        assertEquals(listOf("tt2"), rows[2].items.map { it.id })
        assertEquals(listOf("Plan to Watch", "Watched"), l.rows(emptyList()).map { it.title })
        assertTrue(lib().rows(emptyList()).isEmpty())
    }

    @Test
    fun aRowChangesItsIdWhenItsContentsChange() {
        val l = lib()
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        val before = l.rows(emptyList()).single().id
        assertEquals(before, l.rows(emptyList()).single().id)
        l.set(m("tt2"), LibraryStatus.PlanToWatch)
        assertNotEquals(before, l.rows(emptyList()).single().id)
    }

    @Test
    fun theLibrarySurvivesARestartInTheSwiftShapeAndEachProfileHasItsOwn() {
        val prefs = PrefStore()
        t = 978_307_200_000L + 5_000
        val l = lib(prefs)
        l.set(m("tt1"), LibraryStatus.PlanToWatch)
        val text = prefs.jsonText("library.local")!!
        assertTrue("\"status\":\"planToWatch\"" in text)
        assertTrue("\"added\":5.0" in text)
        assertEquals(listOf("tt1"), lib(prefs).entries.map { it.id })

        l.load("p2")
        assertTrue(l.entries.isEmpty())
        l.set(m("tt9"), LibraryStatus.Watched)
        assertEquals(listOf("tt9"), LocalLibrary(prefs, "p2").entries.map { it.id })
        l.load("default")
        assertEquals(listOf("tt1"), l.entries.map { it.id })
    }

    @Test
    fun anUnreadableEntryIsSkipped() {
        val prefs = PrefStore()
        prefs.setJsonText("library.local", """[{"nope":1},{"item":{"id":"tt1","type":"movie","name":"A"},"status":"watched","added":0}]""")
        val l = lib(prefs)
        assertEquals(listOf("tt1"), l.entries.map { it.id })
        assertEquals(LibraryStatus.Watched, l.entries.single().status)
        assertFalse(l.entries.isEmpty())
        assertNull(l.entry("tt2"))
    }
}

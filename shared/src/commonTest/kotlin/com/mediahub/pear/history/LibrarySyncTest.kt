package com.mediahub.pear.history

import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun title(id: String, name: String = id, year: String? = "2020", type: String = "movie") =
    MetaPreview(id, type, name, releaseInfo = year)

private fun row(status: String, vararg items: MetaPreview) = CatalogRow("simkl-$status", status, items.toList())

private fun saved(item: MetaPreview, status: LibraryStatus, alias: String? = null, added: Long = 1_000L) = LibraryEntry(item, status, alias, added)

private fun played(item: MetaPreview, position: Double, duration: Double = 6000.0) = HistoryEntry(item, "k", position, duration, 0L)

class LibrarySyncPlanTest {
    private fun plan(
        remote: List<CatalogRow> = emptyList(),
        local: List<LibraryEntry> = emptyList(),
        history: List<HistoryEntry> = emptyList(),
        synced: Set<String> = emptySet(),
        since: Long? = null,
    ) = LibrarySync.plan(remote, local, history, synced, since)

    @Test
    fun completedOnSimklWinsOverPlanToWatchHere() {
        val p = plan(remote = listOf(row("completed", title("tt1"))), local = listOf(saved(title("tt1"), LibraryStatus.PlanToWatch)))
        assertEquals(listOf("tt1" to LibraryStatus.Watched), p.pulls.map { it.item.id to it.status })
        assertTrue(p.pushes.isEmpty())
        assertTrue("tt1" in p.synced)
    }

    @Test
    fun watchedHereIsSentAsCompletedWhenSimklHasItAnotherWay() {
        val p = plan(remote = listOf(row("plantowatch", title("tt1"))), local = listOf(saved(title("tt1"), LibraryStatus.Watched)))
        assertEquals(listOf(SimklListAdd("tt1", null, true, "completed")), p.pushes)
        assertTrue(p.pulls.isEmpty())
    }

    @Test
    fun aTitleSimklLacksIsSentToTheListMatchingItsStatus() {
        val p = plan(local = listOf(saved(title("tt1"), LibraryStatus.PlanToWatch), saved(title("tt2", type = "series"), LibraryStatus.Watched)))
        assertEquals(
            listOf(SimklListAdd("tt1", null, true, "plantowatch"), SimklListAdd("tt2", null, false, "completed")),
            p.pushes,
        )
        assertEquals(setOf("tt1", "tt2"), p.pushedIDs)
        assertEquals(setOf("tt1", "tt2"), p.synced)
    }

    @Test
    fun aTitleOnBothSidesLastTimeAndGoneFromSimklNowIsLeftAlone() {
        val old = saved(title("tt1"), LibraryStatus.PlanToWatch, added = 500)
        val p = plan(local = listOf(old), synced = setOf("tt1"), since = 1_000)
        assertTrue(p.pushes.isEmpty())
        assertTrue("tt1" in p.synced)
        // A title saved after the last sync is new, even if its id was seen before: it is sent.
        val fresh = saved(title("tt1"), LibraryStatus.PlanToWatch, added = 2_000)
        assertEquals(1, plan(local = listOf(fresh), synced = setOf("tt1"), since = 1_000).pushes.size)
    }

    @Test
    fun simklOnlyTitlesAreBroughtHereExceptWatchingOnesAndOnesRemovedHere() {
        val remote = listOf(
            row("plantowatch", title("tt5")),
            row("completed", title("tt6")),
            row("watching", title("tt7")),
            row("plantowatch", title("tt8")),
        )
        val p = plan(remote = remote, synced = setOf("tt8"))
        assertEquals(listOf("tt5" to LibraryStatus.PlanToWatch, "tt6" to LibraryStatus.Watched), p.pulls.map { it.item.id to it.status })
        assertTrue("tt8" in p.synced) // still counted as seen on both sides, so it isn't resurrected later
        assertFalse("tt7" in p.synced)
    }

    @Test
    fun otherRowsAreIgnored() {
        val p = plan(remote = listOf(CatalogRow("something-else", "x", listOf(title("tt1")))))
        assertTrue(p.pulls.isEmpty())
    }

    @Test
    fun watchingTitlesSimklLacksAreSentAsWatchingOrCompleted() {
        val h = listOf(
            played(title("tt1"), 100.0),
            played(title("tt2"), 5800.0), // finished movie
            played(title("tt3", type = "series"), 2300.0, 2400.0), // finished episode of a show
            played(title("tt4"), 10.0), // barely started: not sent
        )
        val p = plan(history = h)
        assertEquals(
            listOf(
                SimklListAdd("tt1", null, true, "watching"),
                SimklListAdd("tt2", null, true, "completed"),
                SimklListAdd("tt3", null, false, "watching"),
            ),
            p.pushes,
        )
        assertEquals(setOf("tt1", "tt2", "tt3"), p.pushedIDs)
    }

    @Test
    fun historyForTitlesAlreadySavedOrKnownIsNotSentTwice() {
        val h = listOf(played(title("tt1"), 100.0), played(title("tt2"), 100.0), played(title("tt3"), 100.0))
        val p = plan(
            remote = listOf(row("watching", title("tt2"))),
            local = listOf(saved(title("tt1"), LibraryStatus.PlanToWatch)),
            history = h,
            synced = setOf("tt3"),
        )
        assertEquals(listOf("tt1"), p.pushes.map { it.imdb }) // tt1 as plan to watch from the library, nothing for the history ones
    }

    @Test
    fun titlesAreMatchedByNameAndYearWhenTheIdsDiffer() {
        val remote = listOf(row("plantowatch", title("tt9", "Dune", "2021")))
        val p = plan(remote = remote, local = listOf(saved(title("tmdb:1", "Dune", "2021"), LibraryStatus.Watched)))
        assertEquals(listOf(SimklListAdd(null, 1, true, "completed")), p.pushes) // matched, so only the status is sent
        assertTrue(p.pulls.isEmpty())
    }

    @Test
    fun aTitleWithoutAYearMatchesLooselyByName() {
        val remote = listOf(row("completed", title("tt9", "Dune", "2021")))
        val p = plan(remote = remote, local = listOf(saved(title("tmdb:1", "Dune", null), LibraryStatus.PlanToWatch)))
        assertEquals(1, p.pulls.size)
        assertTrue(p.pushes.isEmpty())
        // A different year is a different title.
        val q = plan(remote = remote, local = listOf(saved(title("tmdb:2", "Dune", "1984"), LibraryStatus.PlanToWatch)))
        assertEquals(1, q.pushes.size)
    }

    @Test
    fun aliasesAreUsedToFindTheOtherSide() {
        val remote = listOf(row("completed", title("tt1")))
        val p = plan(remote = remote, local = listOf(saved(title("tmdb:1", "Other name"), LibraryStatus.PlanToWatch, alias = "tt1")))
        assertEquals(1, p.pulls.size)
        assertTrue(p.pushes.isEmpty())
    }

    @Test
    fun aTitleWithNoUsableIdIsNeverSent() {
        val p = plan(local = listOf(saved(title("kitsu:1"), LibraryStatus.PlanToWatch)))
        assertTrue(p.pushes.isEmpty())
        assertTrue(p.synced.isEmpty())
        assertEquals(listOf(SimklListAdd(null, 7, true, "plantowatch")), plan(local = listOf(saved(title("tmdb:7"), LibraryStatus.PlanToWatch))).pushes)
    }

    @Test
    fun historyPushesAreCappedAtThreeHundred() {
        val h = (1..305).map { played(title("tt$it"), 100.0) }
        assertEquals(300, plan(history = h).pushes.size)
    }
}

private class FakeSimkl(var rows: List<CatalogRow> = emptyList()) : SimklLibrary {
    override var isConnected = true
    override var isSyncing = false
    override var syncError: String? = null
    override val library: List<CatalogRow> get() = rows
    var syncs = 0
    var acceptAdds = true
    val added = ArrayList<List<SimklListAdd>>()

    override suspend fun sync(force: Boolean) {
        syncs++
    }

    override suspend fun add(items: List<SimklListAdd>): Boolean {
        added.add(items)
        return acceptAdds
    }
}

class LibraryPrefsTest {
    private var t = 5_000L
    private val prefs = PrefStore()
    private val library = LocalLibrary(prefs, now = { 1_000L })
    private val history = WatchHistory(prefs)
    private fun libPrefs() = LibraryPrefs(prefs, now = { t })

    @Test
    fun aConnectedSimklIsTheLibraryUntilTheUserChoosesOtherwise() {
        val p = libPrefs()
        assertEquals(LibrarySource.Local, p.source(false))
        assertEquals(LibrarySource.Simkl, p.source(true))
        p.choose(LibrarySource.Local)
        assertEquals(LibrarySource.Local, p.source(true))
        assertEquals(LibrarySource.Local, libPrefs().source(true)) // saved
        assertFalse(p.usesSimkl(true))
        p.choose(LibrarySource.Simkl)
        assertTrue(p.usesSimkl(true))
        assertFalse(p.usesSimkl(false)) // Simkl only counts while connected
    }

    @Test
    fun autoSyncAndTheChoiceAreSavedPerProfile() {
        val p = libPrefs()
        p.setAutoSync(true)
        p.choose(LibrarySource.Local)
        p.load("p2")
        assertFalse(p.autoSync)
        assertEquals(LibrarySource.Simkl, p.source(true))
        assertNotNull(prefs.jsonText("library.prefs"))
        p.setAutoSync(true)
        assertNotNull(prefs.jsonText("library.prefs.p2"))
        p.load("default")
        assertTrue(p.autoSync)
        assertEquals(LibrarySource.Local, p.source(true))
    }

    @Test
    fun syncReceivesAndSendsAndRemembersWhenItRan() = runTest {
        library.set(title("tt1"), LibraryStatus.PlanToWatch)
        val simkl = FakeSimkl(listOf(row("completed", title("tt2"))))
        val p = libPrefs()
        p.sync(simkl, library, history, force = true)
        assertEquals("Received 1 · Sent 1", p.report)
        assertEquals(listOf("tt2"), library.entries.filter { it.status == LibraryStatus.Watched }.map { it.id })
        assertEquals(listOf(listOf(SimklListAdd("tt1", null, true, "plantowatch"))), simkl.added)
        assertEquals(2, simkl.syncs) // once to read, once more to show what was sent
        assertEquals(5_000L, p.lastSync)
        assertFalse(p.isSyncing)
    }

    @Test
    fun nothingToDoSaysSo() = runTest {
        library.set(title("tt1"), LibraryStatus.PlanToWatch)
        val simkl = FakeSimkl(listOf(row("plantowatch", title("tt1"))))
        val p = libPrefs()
        p.sync(simkl, library, history, force = true)
        assertEquals("Already in sync.", p.report)
        assertTrue(simkl.added.isEmpty())
        assertEquals(1, simkl.syncs)
    }

    @Test
    fun syncIsThrottledToFifteenMinutesUnlessForced() = runTest {
        val simkl = FakeSimkl()
        val p = libPrefs()
        p.sync(simkl, library, history, force = false)
        assertEquals(1, simkl.syncs)
        t += 14 * 60_000
        p.sync(simkl, library, history, force = false)
        assertEquals(1, simkl.syncs)
        p.sync(simkl, library, history, force = true)
        assertEquals(2, simkl.syncs)
        t += 16 * 60_000
        p.sync(simkl, library, history, force = false)
        assertEquals(3, simkl.syncs)
    }

    @Test
    fun aFailedSendIsReportedAndRetriedNextTime() = runTest {
        library.set(title("tt1"), LibraryStatus.PlanToWatch)
        val simkl = FakeSimkl()
        simkl.acceptAdds = false
        val p = libPrefs()
        p.sync(simkl, library, history, force = true)
        assertTrue(p.report!!.startsWith("Couldn't send to Simkl."))
        assertNull(p.lastSync) // not counted as a completed sync
        simkl.acceptAdds = true
        p.sync(simkl, library, history, force = true)
        assertEquals(2, simkl.added.size)
        assertEquals("Received 0 · Sent 1", p.report)
    }

    @Test
    fun aSimklErrorStopsTheMergeAndIsShown() = runTest {
        library.set(title("tt1"), LibraryStatus.PlanToWatch)
        val simkl = FakeSimkl(listOf(row("completed", title("tt2"))))
        simkl.syncError = "Simkl asked the app to slow down."
        val p = libPrefs()
        p.sync(simkl, library, history, force = true)
        assertEquals("Simkl asked the app to slow down.", p.report)
        assertTrue(simkl.added.isEmpty())
        assertEquals(1, library.entries.size)
        assertFalse(p.isSyncing)
    }

    @Test
    fun notConnectedMeansNothingHappens() = runTest {
        val simkl = FakeSimkl()
        simkl.isConnected = false
        libPrefs().sync(simkl, library, history, force = true)
        assertEquals(0, simkl.syncs)
    }

    @Test
    fun aTitleRemovedOnSimklAfterASyncIsNotSentBack() = runTest {
        library.set(title("tt1"), LibraryStatus.PlanToWatch) // added at 1000
        val simkl = FakeSimkl(listOf(row("plantowatch", title("tt1"))))
        val p = libPrefs()
        p.sync(simkl, library, history, force = true) // both sides have it: remembered
        t += 60_000
        simkl.rows = emptyList() // removed on Simkl
        p.sync(simkl, library, history, force = true)
        assertTrue(simkl.added.isEmpty())
        assertEquals("Already in sync.", p.report)
        assertEquals(1, library.entries.size) // and it stays here
    }
}

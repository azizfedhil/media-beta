package com.mediahub.pear.history

import com.mediahub.pear.LocalParts
import com.mediahub.pear.data.PrefStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchLogTest {
    private var parts = LocalParts(2026, 10, 5, 21)
    private var t = 0L

    private fun log(prefs: PrefStore = PrefStore()) = WatchLog(prefs, now = { t }, localTime = { parts })

    @Test
    fun secondsLandInTheHourOfTheDayTheyWerePlayed() {
        val l = log()
        l.record(30.0)
        assertEquals(30, l.days["2026-10-05"]!![21])
        assertEquals(24, l.days["2026-10-05"]!!.size)
        parts = LocalParts(2026, 10, 5, 22)
        l.record(29.6) // rounds
        l.record(10.0)
        assertEquals(40, l.days["2026-10-05"]!![22])
        assertEquals(70.0, l.totalSeconds)
        assertEquals(40.0, l.hourTotals[22])
    }

    @Test
    fun ticksOfHalfASecondOrLessAndOfTenMinutesOrMoreAreIgnored() {
        val l = log()
        l.record(0.5)
        l.record(0.0)
        l.record(-3.0)
        l.record(600.0)
        l.record(5_000.0)
        assertTrue(l.isEmpty)
        l.record(599.0)
        assertEquals(599.0, l.totalSeconds)
    }

    @Test
    fun theFirstTickIsSavedAtOnceAndLaterOnesAreHeldBackForAMinute() {
        val prefs = PrefStore()
        val l = log(prefs)
        l.record(30.0)
        val first = prefs.jsonText("watch.log")
        assertNotNull(first)
        l.record(10.0)
        assertEquals(first, prefs.jsonText("watch.log")) // not rewritten on every tick
        l.flush()
        assertNotEquals(first, prefs.jsonText("watch.log"))
        t = 61_000
        l.record(5.0)
        assertTrue("45" in prefs.jsonText("watch.log")!!) // a minute later it saves again by itself
    }

    @Test
    fun theLogSurvivesARestartInTheSwiftShape() {
        val prefs = PrefStore()
        log(prefs).also { it.record(30.0); it.flush() }
        val hours = List(24) { if (it == 21) 30 else 0 }.joinToString(",", "[", "]")
        assertEquals("{\"2026-10-05\":$hours}", prefs.jsonText("watch.log"))
        assertEquals(30.0, log(prefs).totalSeconds)
    }

    @Test
    fun theAverageCountsOnlyDaysSinceTrackingBegan() {
        val l = log()
        parts = LocalParts(2026, 10, 1, 10)
        l.record(60.0)
        parts = LocalParts(2026, 10, 5, 10)
        l.record(120.0)
        assertEquals("2026-10-01", l.firstDay)
        assertEquals(36.0, l.averageDailySeconds()) // 180 s over the 5 days since the first one
    }

    @Test
    fun theAverageLooksBackAtMostTheWindow() {
        val l = log()
        parts = LocalParts(2026, 8, 1, 10)
        l.record(300.0) // older than 30 days: outside the window
        parts = LocalParts(2026, 10, 5, 10)
        l.record(300.0)
        assertEquals(10.0, l.averageDailySeconds(30)) // 300 s over 30 days
        assertNull(log().averageDailySeconds())
    }

    @Test
    fun anOldLogIsTrimmedToAboutFourHundredDays() {
        val l = log()
        for (i in 0 until 431) {
            parts = LocalParts(2000 + i, 1, 1, 0)
            l.record(10.0)
        }
        l.flush()
        assertEquals(400, l.days.size)
        assertNull(l.days["2000-01-01"])
        assertNotNull(l.days["2430-01-01"])
    }

    @Test
    fun eachProfileHasItsOwnLogAndSwitchingSavesTheOldOne() {
        val prefs = PrefStore()
        val l = log(prefs)
        l.record(30.0)
        l.record(10.0) // held back: only the first tick was saved
        l.load("p2")
        assertTrue(l.isEmpty)
        assertNotNull(prefs.jsonText("watch.log"))
        assertEquals(40.0, log(prefs).totalSeconds) // the switch flushed the pending tick
        l.record(5.0)
        l.flush()
        assertNotNull(prefs.jsonText("watch.log.p2"))
    }

    @Test
    fun reloadDropsUnsavedDataAndReadsStorage() {
        val prefs = PrefStore()
        val l = log(prefs)
        l.record(30.0)
        l.record(10.0)
        l.reload()
        assertEquals(30.0, l.totalSeconds)
    }

    @Test
    fun daysAreCountedFromTheEpoch() {
        assertEquals(0L, daysFromCivil(1970, 1, 1))
        assertEquals(11_017L, daysFromCivil(2000, 3, 1))
        assertEquals(1L, daysFromCivil(2026, 10, 6) - daysFromCivil(2026, 10, 5))
        assertEquals(31L, daysFromCivil(2026, 11, 1) - daysFromCivil(2026, 10, 1))
    }
}

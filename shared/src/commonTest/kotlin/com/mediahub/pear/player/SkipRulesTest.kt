package com.mediahub.pear.player

import com.mediahub.pear.meta.SkipSegment
import com.mediahub.pear.player.ui.SkipRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipRulesTest {
    private val intro = SkipSegment(SkipSegment.Kind.Intro, 12.7, 60.0)
    private val credits = SkipSegment(SkipSegment.Kind.Credits, 2_400.0, null)

    @Test
    fun anIntroOffersItsSkipToItsEnd() {
        val c = SkipRules.choose(20.0, 1_500.0, listOf(intro), hasNext = true, manualSeconds = null)!!
        assertEquals("Skip Intro", c.label)
        assertEquals("forward.fill", c.symbol)
        assertEquals(60.0, c.target)
        assertFalse(c.isFallback)
    }

    @Test
    fun aSegmentStartsHalfASecondEarlyAndEndsOneAndAHalfSecondsBeforeItsEnd() {
        assertEquals("Skip Intro", SkipRules.choose(12.3, 1_500.0, listOf(intro), false, null)?.label)
        assertNull(SkipRules.choose(12.0, 1_500.0, listOf(intro), false, null))
        assertNull(SkipRules.choose(58.6, 1_500.0, listOf(intro), false, null))
    }

    @Test
    fun creditsWithAnEpisodeAfterThemOfferNextEpisode() {
        val c = SkipRules.choose(2_450.0, 2_500.0, listOf(credits), hasNext = true, manualSeconds = null)!!
        assertEquals("Next Episode", c.label)
        assertNull(c.target)
    }

    @Test
    fun creditsWithoutAnEpisodeAfterThemSkipToTheEnd() {
        val c = SkipRules.choose(2_450.0, 2_500.0, listOf(credits), hasNext = false, manualSeconds = null)!!
        assertEquals("Skip Credits", c.label)
        assertEquals(2_500.0, c.target)
    }

    @Test
    fun theLastMinuteOffersNextOnlyAfterTheFirstMinute() {
        assertEquals("Next Episode", SkipRules.choose(1_480.0, 1_500.0, emptyList(), true, null)?.label)
        assertNull(SkipRules.choose(1_480.0, 1_500.0, emptyList(), false, null))
        assertNull(SkipRules.choose(30.0, 40.0, emptyList(), true, null))
    }

    @Test
    fun theManualSkipJumpsAheadWhenGiven() {
        val c = SkipRules.choose(10.0, 1_500.0, emptyList(), hasNext = false, manualSeconds = 85.0)!!
        assertEquals("Skip 85s", c.label)
        assertEquals("goforward", c.symbol)
        assertEquals(95.0, c.target)
        assertTrue(c.isFallback)
    }

    @Test
    fun aSegmentBeatsTheManualSkip() {
        assertEquals("Skip Intro", SkipRules.choose(20.0, 1_500.0, listOf(intro), false, manualSeconds = 85.0)?.label)
    }

    @Test
    fun nothingWithoutAFileLength() {
        assertNull(SkipRules.choose(1.0, 0.0, listOf(intro), hasNext = true, manualSeconds = 85.0))
    }

    @Test
    fun theManualButtonIsOfferedOnlyEarlyInAnEpisodeWithoutTimestamps() {
        fun offered(
            loaded: Boolean = true,
            midway: Boolean = false,
            hasSegments: Boolean = false,
            configured: Boolean = true,
            used: Boolean = false,
            position: Double = 10.0,
            duration: Double = 1_500.0,
        ) = SkipRules.manualOffered(loaded, midway, hasSegments, configured, used, position, duration)

        assertTrue(offered())
        assertFalse(offered(loaded = false))
        assertFalse(offered(midway = true))
        assertFalse(offered(hasSegments = true))
        assertFalse(offered(configured = false))
        assertFalse(offered(used = true))
        assertFalse(offered(position = 2.0))
        assertFalse(offered(position = 421.0))
        assertFalse(offered(duration = 0.0))
    }
}

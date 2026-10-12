package com.mediahub.pear.brand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PearGeometryTest {
    @Test
    fun theIntroIsOneShotAndRunsForTheSwiftLength() {
        // 600 + 800 + 500 + 220 + 300 + 220 + 300 + 1000, as in PearGeometry.swift.
        assertEquals(3940.0, assertNotNull(PearGeometry.duration(PearMode.Intro)), 1e-9)
    }

    @Test
    fun theLoopsHaveNoEnd() {
        assertNull(PearGeometry.duration(PearMode.Loop))
        assertNull(PearGeometry.duration(PearMode.Loader))
    }

    @Test
    fun theIntroStartsOnTheMonogramTileAndRestsWithoutIt() {
        assertEquals(1.0, PearGeometry.frame(0.0, PearMode.Intro).tile, 1e-9)
        assertEquals(0.0, PearGeometry.frame(10_000.0, PearMode.Intro).tile, 1e-9)
        assertEquals(PearGeometry.restFrame(PearMode.Intro).tile, PearGeometry.frame(3_940.0, PearMode.Intro).tile, 1e-9)
    }

    @Test
    fun aLoopWrapsAroundItsLength() {
        // The loader is 200 + 700 + 300 + 240 + 300 + 240 + 200 + 700 = 2880 ms.
        val once = PearGeometry.frame(123.0, PearMode.Loader)
        val again = PearGeometry.frame(123.0 + 2 * 2880.0, PearMode.Loader)
        assertEquals(once.tile, again.tile, 1e-9)
        assertEquals(once.cream.size, again.cream.size)
    }

    @Test
    fun aMorphDrawsResampledPolygonsOfEqualLength() {
        // Inside the first morph of the loader (after its 200 ms hold), halfway through.
        val frame = PearGeometry.frame(200.0 + 350.0, PearMode.Loader)
        val polys = frame.cream.filterIsInstance<PearShape.Poly>()
        assertTrue(polys.isNotEmpty())
        assertTrue(polys.all { it.pts.size == 128 })
    }

    @Test
    fun theWordmarkHasItsSevenLettersAndAFullStop() {
        assertEquals(7, PearGlyphs.letters.size)
        assertTrue(PearGlyphs.dot.cmds.isNotEmpty())
        assertEquals(245.1, PearGlyphs.box.width, 1e-9)
    }
}

class PearOutlineTest {
    @Test
    fun aSquarePathParsesToThreePoints() {
        val square = Outline.parse("M0 0L10 0L10 10Z")
        assertEquals(3, square.flattened().size)
    }

    @Test
    fun resamplingSpacesPointsEvenlyAlongTheOutline() {
        // Perimeter 40, so eight points sit 5 apart along the square.
        val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val out = Outline.resample(square, 8)
        assertEquals(8, out.size)
        assertEquals(0.0, out[0].x, 1e-9)
        assertEquals(5.0, out[1].x, 1e-9)
        assertEquals(0.0, out[1].y, 1e-9)
    }

    @Test
    fun aRoundedRectangleFlattensToAClosedLoop() {
        val rect = Outline.rrect(0.0, 0.0, 100.0, 50.0, 10.0)
        val pts = rect.flattened()
        assertTrue(pts.size > 8)
        assertTrue(pts.all { it.x in -0.001..100.001 && it.y in -0.001..50.001 })
    }
}

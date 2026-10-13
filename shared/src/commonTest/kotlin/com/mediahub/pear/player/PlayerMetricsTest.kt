package com.mediahub.pear.player

import com.mediahub.pear.player.ui.PLAYER_COMPACT_SHORT_SIDE
import com.mediahub.pear.player.ui.playerMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerMetricsTest {
    @Test
    fun aFullscreen1080pWindowKeepsTheDesktopLookAtEveryCommonScaling() {
        // 1080, 864, 720 and 540 dp tall: 1080p at 100%, 125%, 150% and 200% scaling.
        for (shortSide in listOf(1080f, 864f, 720f, 540f)) {
            val m = playerMetrics(shortSide)
            assertEquals(1.25f, m.scale, "scale at $shortSide dp")
            assertEquals(72f, m.sideMarginDp, "side margin at $shortSide dp")
            assertEquals(44f, m.bottomMarginDp, "bottom margin at $shortSide dp")
        }
    }

    @Test
    fun aPhoneInLandscapeGetsTheIosSizes() {
        val m = playerMetrics(390f)
        assertEquals(1f, m.scale)
        assertEquals(24f, m.sideMarginDp)
        assertEquals(6f, m.bottomMarginDp)
    }

    @Test
    fun theBlendRunsFromIosToDesktopWithoutAJump() {
        assertEquals(1f, playerMetrics(PLAYER_COMPACT_SHORT_SIDE).scale)
        var last = 0f
        var side = PLAYER_COMPACT_SHORT_SIDE
        while (side <= 500f) {
            val scale = playerMetrics(side).scale
            assertTrue(scale >= last, "scale fell at $side dp")
            last = scale
            side += 5f
        }
    }
}

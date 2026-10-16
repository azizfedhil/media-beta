package com.mediahub.pear.player

import com.mediahub.pear.player.ui.panelWidthDp
import com.mediahub.pear.player.ui.playerMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelWidthTest {
    @Test
    fun aPanelKeepsItsWidthWhenTheWindowHasRoom() {
        // A 1080p fullscreen window has far more room than a 360 dp panel: the desktop width stays.
        val m = playerMetrics(1080f).copy(availableWidthDp = 1392f)
        assertEquals(360f, m.panelWidthDp(360f))
    }

    @Test
    fun aPanelNarrowsToTheWindow() {
        val m = playerMetrics(390f).copy(availableWidthDp = 300f)
        assertEquals(300f, m.panelWidthDp(360f))
    }

    @Test
    fun aPanelNeverGoesBelowTheFloor() {
        val m = playerMetrics(390f).copy(availableWidthDp = 120f)
        assertEquals(220f, m.panelWidthDp(360f))
    }
}

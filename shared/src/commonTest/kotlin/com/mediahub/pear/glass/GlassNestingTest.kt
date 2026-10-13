package com.mediahub.pear.glass

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassNestingTest {
    @Test
    fun aSurfaceInsideAnotherIsFlatWhateverTheStyle() {
        for (mode in GlassMode.entries) {
            assertEquals(GlassMode.Flat, effectiveGlassMode(hasScene = true, lowBattery = false, nested = true, mode = mode))
        }
    }

    @Test
    fun aTopLevelSurfaceKeepsTheChosenStyle() {
        for (mode in GlassMode.entries) {
            assertEquals(mode, effectiveGlassMode(hasScene = true, lowBattery = false, nested = false, mode = mode))
        }
    }

    @Test
    fun noSceneOrLowBatteryIsFlat() {
        assertEquals(GlassMode.Flat, effectiveGlassMode(hasScene = false, lowBattery = false, nested = false, mode = GlassMode.Glass))
        assertEquals(GlassMode.Flat, effectiveGlassMode(hasScene = true, lowBattery = true, nested = false, mode = GlassMode.Glass))
    }
}

package com.mediahub.pear.glass

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassSettingsTest {
    @Test
    fun defaultsToGlass() {
        val s = GlassStore()
        assertEquals(GlassMode.Glass, s.mode)
        assertEquals(GlassPerformance.Balanced, s.performance)
    }

    @Test
    fun modeCanBeChanged() {
        val s = GlassStore()
        s.mode = GlassMode.Flat
        assertEquals(GlassMode.Flat, s.mode)
    }

    @Test
    fun hasFourStyles() {
        assertEquals(listOf("Frosted blur", "Glass", "Glass clear", "Flat"), GlassMode.entries.map { it.label })
    }
}

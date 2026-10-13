package com.mediahub.pear.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun storeDefaultsMatchTheIosApp() {
        val s = ThemeStore()
        assertEquals(Accents.DEFAULT_HEX, s.accentHex)
        assertEquals(Appearance.Dark, s.appearance) // Theme.forceDark = true
        assertFalse(s.isSystemAccent)
    }

    @Test
    fun setAccentNormalizesAndRejectsGarbage() {
        val s = ThemeStore()
        s.setAccent("22c55e")
        assertEquals("#22C55E", s.accentHex)
        s.setAccent(Accents.SYSTEM)
        assertTrue(s.isSystemAccent)
        s.setAccent("not a colour")
        assertEquals(Accents.DEFAULT_HEX, s.accentHex)
    }

    @Test
    fun darkAndLightPalettesDiffer() {
        val d = pearColors(Accents.DEFAULT_HEX, dark = true)
        val l = pearColors(Accents.DEFAULT_HEX, dark = false)
        assertTrue(d.isDark)
        assertFalse(l.isDark)
        assertTrue(d.background != l.background)
        assertEquals(d.accent, l.accent)
    }

    @Test
    fun swatchNamesAreUnique() {
        val names = pearColors(Accents.DEFAULT_HEX, true).swatches().map { it.first }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun typeScaleHasElevenStyles() {
        val t = PearTypography(androidx.compose.ui.text.font.FontFamily.Default)
        assertEquals(11, t.all().size)
        assertEquals(17f, t.body.fontSize.value)
        assertEquals(13f, t.footnote.fontSize.value)
    }

    @Test
    fun radiiAreInIncreasingOrder() {
        val order = listOf(PearRadii.xs, PearRadii.sm, PearRadii.md, PearRadii.lg, PearRadii.card, PearRadii.sheet, PearRadii.pill)
        assertEquals(order.sortedBy { it.value }, order)
    }
}

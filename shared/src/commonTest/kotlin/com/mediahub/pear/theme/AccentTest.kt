package com.mediahub.pear.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentTest {
    @Test
    fun parsesHexWithAndWithoutHash() {
        assertEquals(Color(1f, 0f, 0f), Accents.parseHex("#FF0000"))
        assertEquals(Color(0f, 1f, 0f), Accents.parseHex("00ff00"))
        assertNull(Accents.parseHex("#12345"))
        assertNull(Accents.parseHex("#GGGGGG"))
        assertNull(Accents.parseHex(""))
    }

    @Test
    fun hexRoundTripsForEveryPreset() {
        for (p in Accents.presets) {
            assertEquals(p.hex, Accents.toHex(Accents.parseHex(p.hex)!!))
        }
    }

    @Test
    fun hasTheEightIosPresets() {
        assertEquals(
            listOf("Violet", "Pink", "Blue", "Cyan", "Green", "Gold", "Orange", "Red"),
            Accents.presets.map { it.name },
        )
        assertEquals("#7D5CFF", Accents.DEFAULT_HEX)
    }

    @Test
    fun normalizeKeepsSystemAndFallsBackToDefault() {
        assertEquals(Accents.SYSTEM, Accents.normalize("system"))
        assertEquals(Accents.SYSTEM, Accents.normalize(" SYSTEM "))
        assertEquals("#2F80FF", Accents.normalize("2f80ff"))
        assertEquals(Accents.DEFAULT_HEX, Accents.normalize("nonsense"))
        assertEquals(Accents.DEFAULT_HEX, Accents.normalize(null))
    }

    @Test
    fun onAccentIsBlackOnLightAndWhiteOnDark() {
        assertEquals(Color.Black, Accents.contrastingForeground(Accents.parseHex("#F5B301")!!)) // gold
        assertEquals(Color.White, Accents.contrastingForeground(Accents.parseHex("#7D5CFF")!!)) // violet
        assertEquals(Color.Black, Accents.contrastingForeground(Color.White))
        assertEquals(Color.White, Accents.contrastingForeground(Color.Black))
    }

    @Test
    fun systemAccentIsNeutral() {
        val dark = Accents.resolve(Accents.SYSTEM, dark = true)
        assertEquals(Color.White, dark.accent)
        assertEquals(Color.Black, dark.onAccent)
        val light = Accents.resolve(Accents.SYSTEM, dark = false)
        assertEquals(Color.Black, light.accent)
        assertEquals(Color.White, light.onAccent)
    }

    @Test
    fun neighbourShiftsHueForColourAndBrightnessForGrey() {
        val violet = Accents.parseHex("#7D5CFF")!!
        assertNotEquals(violet, Accents.neighbour(violet))
        // hue moves by 0.1 of the circle, saturation is capped at 0.9, brightness is capped at 1
        val before = Accents.toHsb(violet)
        val after = Accents.toHsb(Accents.neighbour(violet))
        assertEquals((before[0] + 0.1f) % 1f, after[0], 0.01f)
        assertTrue(after[1] <= 0.9f + 0.01f)
        // white has no hue: it steps down in brightness
        val grey = Accents.neighbour(Color.White)
        assertEquals(0.7f, grey.red, 0.01f)
        assertEquals(grey.red, grey.green, 0.001f)
        // black steps up
        assertEquals(0.3f, Accents.neighbour(Color.Black).red, 0.01f)
    }

    @Test
    fun hsbRoundTrips() {
        for (p in Accents.presets) {
            val c = Accents.parseHex(p.hex)!!
            val hsb = Accents.toHsb(c)
            val back = Accents.fromHsb(hsb[0], hsb[1], hsb[2])
            assertEquals(c.red, back.red, 0.01f)
            assertEquals(c.green, back.green, 0.01f)
            assertEquals(c.blue, back.blue, 0.01f)
        }
    }
}

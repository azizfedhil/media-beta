package com.mediahub.pear.home

import androidx.compose.ui.graphics.Color
import com.mediahub.pear.theme.Accents
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AmbientToneTest {
    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.01f, "$what: expected $expected, was $actual")

    @Test
    fun aDarkAverageIsLiftedToTheFloorOfTheBand() {
        val out = Accents.toHsb(AmbientTone.tune(Color(0.05f, 0.10f, 0.12f)))
        assertNear(AmbientTone.MIN_BRIGHTNESS, out[2], "brightness")
    }

    @Test
    fun aBrightAverageIsHeldBelowTheCeilingOfTheBand() {
        val out = Accents.toHsb(AmbientTone.tune(Color(0.95f, 0.95f, 0.90f)))
        assertNear(AmbientTone.MAX_BRIGHTNESS, out[2], "brightness")
    }

    @Test
    fun theHueIsKept() {
        val input = Accents.fromHsb(0.5f, 0.4f, 0.5f)
        assertNear(0.5f, Accents.toHsb(AmbientTone.tune(input))[0], "hue")
    }

    @Test
    fun saturationIsRaisedTheWayIosRaisesIt() {
        // min(1, s * 1.3 + 0.08): 0.4 becomes 0.6.
        val input = Accents.fromHsb(0.5f, 0.4f, 0.5f)
        assertNear(0.6f, Accents.toHsb(AmbientTone.tune(input))[1], "saturation")
    }
}

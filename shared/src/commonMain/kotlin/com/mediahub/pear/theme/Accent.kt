package com.mediahub.pear.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One of the accent presets offered in Settings. Same names and values as `Theme.presets` in MediaHubApp.swift. */
class AccentPreset(val name: String, val hex: String)

/** The three colours derived from the user's accent choice. */
class AccentColors(val accent: Color, val accent2: Color, val onAccent: Color)

/**
 * Accent colour rules, ported from `ThemeStore` in MediaHubApp.swift. The stored value (key `ui.accent`) is
 * either `"#RRGGBB"` or [SYSTEM] (the neutral look: white in dark mode, black in light mode).
 * Everything here is plain math so it can be unit-tested without a UI.
 */
object Accents {
    const val DEFAULT_HEX = "#7D5CFF" // electric violet
    const val SYSTEM = "system"

    val presets: List<AccentPreset> = listOf(
        AccentPreset("Violet", "#7D5CFF"), AccentPreset("Pink", "#FF5C8D"),
        AccentPreset("Blue", "#2F80FF"), AccentPreset("Cyan", "#14B8D4"),
        AccentPreset("Green", "#22C55E"), AccentPreset("Gold", "#F5B301"),
        AccentPreset("Orange", "#FF8A1F"), AccentPreset("Red", "#EF4444"),
    )

    /** "#RRGGBB" or "RRGGBB", otherwise null. */
    fun parseHex(hex: String): Color? {
        var s = hex.trim()
        if (s.startsWith("#")) s = s.substring(1)
        if (s.length != 6) return null
        val v = s.toLongOrNull(16) ?: return null
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f)
    }

    /** "#RRGGBB", upper case. */
    fun toHex(color: Color): String {
        fun part(f: Float): String = (f * 255f + 0.5f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
        return "#" + part(color.red) + part(color.green) + part(color.blue)
    }

    /** A stored value cleaned up: [SYSTEM] stays, a valid hex becomes "#RRGGBB", anything else is the default. */
    fun normalize(stored: String?): String {
        if (stored == null) return DEFAULT_HEX
        if (stored.trim().equals(SYSTEM, ignoreCase = true)) return SYSTEM
        val c = parseHex(stored) ?: return DEFAULT_HEX
        return toHex(c)
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(c: Color): Float {
        fun lin(v: Float): Float = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
    }

    /** Black on light colours, white on dark ones. */
    fun contrastingForeground(c: Color): Color = if (luminance(c) > 0.5f) Color.Black else Color.White

    /**
     * A neighbouring hue, for gradients (progress bars, glows). Near-white and grey accents have no hue to shift,
     * so their brightness steps down (or up) instead. Same arithmetic as `ThemeStore.accent2`.
     */
    fun neighbour(c: Color): Color {
        val hsb = toHsb(c)
        val h = hsb[0]
        val s = hsb[1]
        val b = hsb[2]
        if (s < 0.12f) {
            val v = if (b > 0.5f) max(b - 0.3f, 0f) else min(b + 0.3f, 1f)
            return Color(red = v, green = v, blue = v)
        }
        val nh = (h + 0.1f) % 1f
        return fromHsb(nh, min(s, 0.9f), min(b + 0.1f, 1f))
    }

    /** Resolve the stored accent for the current appearance. */
    fun resolve(stored: String, dark: Boolean): AccentColors {
        if (stored == SYSTEM) {
            val accent = if (dark) Color.White else Color.Black
            val accent2 = if (dark) Color(0.68f, 0.68f, 0.68f) else Color(0.32f, 0.32f, 0.32f)
            return AccentColors(accent, accent2, if (dark) Color.Black else Color.White)
        }
        val base = parseHex(stored) ?: parseHex(DEFAULT_HEX)!!
        return AccentColors(base, neighbour(base), contrastingForeground(base))
    }

    // --- HSB helpers (hue, saturation, brightness all 0..1) ---

    internal fun toHsb(c: Color): FloatArray {
        val r = c.red
        val g = c.green
        val b = c.blue
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val s = if (mx == 0f) 0f else d / mx
        var h = 0f
        if (d != 0f) {
            h = when (mx) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
            if (h < 0f) h += 1f
        }
        return floatArrayOf(h, s, mx)
    }

    internal fun fromHsb(h: Float, s: Float, v: Float): Color {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        return when (i % 6) {
            0 -> Color(v, t, p)
            1 -> Color(q, v, p)
            2 -> Color(p, v, t)
            3 -> Color(p, q, v)
            4 -> Color(t, p, v)
            else -> Color(v, p, q)
        }
    }
}

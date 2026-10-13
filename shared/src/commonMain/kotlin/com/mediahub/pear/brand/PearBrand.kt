package com.mediahub.pear.brand

import androidx.compose.ui.graphics.Color

// Brand palette and the "pear." letterforms (Batch brand). Spec: `Brand` and `PearGlyphs` in PearGeometry.swift and
// Features/PearLogo.swift. The glyph strings are copied from the Swift file, so the two apps draw the same letters.

/** Brand palette (matches the logo files). */
object PearBrand {
    val accent = Color(0xFF7C6CFF) // #7C6CFF, the full stop is always this violet
    val cream = Color(0xFFF5F1EA)  // #F5F1EA
    val ink = Color(0xFF121214)    // #121214
    val tile = Color(0xFF1A1A1F)   // #1A1A1F
    val splash = Color(0xFF0B0B0D) // #0B0B0D
}

/** Letter outlines of the "pear." wordmark, in the 380 x 240 animation canvas. */
internal object PearGlyphs {
    /** Tight bounds of the wordmark inside the canvas coordinates. */
    val box = GlyphBox(x = 68.3, y = 88.6, width = 245.1, height = 84.5)

    /** The letters p, e, a, r with their counters. */
    val letters: List<Outline> by lazy { listOf(pO, pH, eO, eH, aO, aH, rO).map { Outline.parse(it) } }

    /** The accent full stop. */
    val dot: Outline by lazy { Outline.parse(dotPath) }

    internal const val pO = "M68.3 173.1L80.7 173.1L80.7 154L80.4 143.9C85.3 148.2 90.7 150.7 95.9 150.7C109.2 150.7 121.3 139 121.3 118.7C121.3 100.4 112.9 88.6 98 88.6C91.3 88.6 84.9 92.2 79.7 96.5L79.5 96.5L78.5 90L68.3 90Z"
    internal const val pH = "M93.5 140.3C89.9 140.3 85.3 138.9 80.7 135L80.7 106.1C85.7 101.4 90.1 98.9 94.7 98.9C104.6 98.9 108.5 106.5 108.5 118.9C108.5 132.6 102.1 140.3 93.5 140.3Z"
    internal const val eO = "M159.7 150.7C167.3 150.7 174.2 148 179.6 144.3L175.3 136.5C171 139.3 166.5 140.9 161.2 140.9C151 140.9 143.9 134.1 142.9 122.8L181.3 122.8C181.6 121.3 181.9 119 181.9 116.5C181.9 99.9 173.5 88.6 157.8 88.6C144 88.6 130.8 100.4 130.8 119.6C130.8 139.2 143.5 150.7 159.7 150.7Z"
    internal const val eH = "M142.8 114.3C144 103.9 150.6 98.3 158 98.3C166.6 98.3 171.2 104.1 171.2 114.3Z"
    internal const val aO = "M208.7 150.7C215.8 150.7 222.1 147 227.5 142.4L227.9 142.4L228.9 149.2L239 149.2L239 113.6C239 97.8 232.2 88.6 217.5 88.6C208.1 88.6 199.8 92.4 193.7 96.3L198.3 104.7C203.3 101.5 209.1 98.7 215.4 98.7C224.1 98.7 226.5 104.7 226.6 111.5C202 114.1 191.3 120.7 191.3 133.5C191.3 143.9 198.5 150.7 208.7 150.7Z"
    internal const val aH = "M212.5 140.8C207.2 140.8 203.2 138.4 203.2 132.5C203.2 126 209.1 121.5 226.6 119.4L226.6 133.8C221.8 138.3 217.6 140.8 212.5 140.8Z"
    internal const val rO = "M255.4 149.2L267.8 149.2L267.8 112.4C271.5 103 277.3 99.6 282.1 99.6C284.6 99.6 286 100 288.1 100.6L290.3 89.7C288.4 89 286.6 88.6 283.7 88.6C277.3 88.6 271 93.1 266.8 100.6L266.6 100.6L265.5 90L255.4 90Z"
    internal const val dotPath = "M305 150.7C309.7 150.7 313.4 146.9 313.4 141.9C313.4 136.8 309.7 133.2 305 133.2C300.4 133.2 296.7 136.8 296.7 141.9C296.7 146.9 300.4 150.7 305 150.7Z"
}

/** A rectangle in canvas coordinates. */
internal class GlyphBox(val x: Double, val y: Double, val width: Double, val height: Double)

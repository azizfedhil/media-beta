package com.mediahub.pear.brand

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

// Vector outlines for the "pear." animation and wordmark (Batch brand). Spec: `Outline` in PearGeometry.swift.
// Pure Kotlin, no Compose, so the geometry is unit tested. Drawing converts these to Compose paths.

/** A point in the animation canvas (380 x 240). */
internal data class Pt(val x: Double, val y: Double)

internal sealed interface Cmd {
    data class Move(val p: Pt) : Cmd
    data class Line(val p: Pt) : Cmd
    data class Cubic(val c1: Pt, val c2: Pt, val p: Pt) : Cmd
    data object Close : Cmd
}

/** A closed outline, kept as exact curves for drawing and flattened to points for morphing. */
internal class Outline(val cmds: List<Cmd>) {

    /** A dense polyline that follows the curves (24 steps per curve). */
    fun flattened(steps: Int = 24): List<Pt> {
        val pts = ArrayList<Pt>()
        var cur = Pt(0.0, 0.0)
        for (c in cmds) {
            when (c) {
                is Cmd.Move -> { pts.add(c.p); cur = c.p }
                is Cmd.Line -> { pts.add(c.p); cur = c.p }
                is Cmd.Cubic -> {
                    for (i in 1..steps) {
                        val t = i.toDouble() / steps
                        val u = 1 - t
                        pts.add(
                            Pt(
                                u * u * u * cur.x + 3 * u * u * t * c.c1.x + 3 * u * t * t * c.c2.x + t * t * t * c.p.x,
                                u * u * u * cur.y + 3 * u * u * t * c.c1.y + 3 * u * t * t * c.c2.y + t * t * t * c.p.y,
                            ),
                        )
                    }
                    cur = c.p
                }
                Cmd.Close -> Unit
            }
        }
        return pts
    }

    companion object {
        /** Circle, starting at its left point. [ccw] reverses the direction. */
        fun circle(cx: Double, cy: Double, r: Double, ccw: Boolean = false): Outline {
            val b = OutlineBuilder()
            val s = if (ccw) -1.0 else 1.0
            val c = Pt(cx, cy)
            b.move(cx - r, cy)
            b.arc(c, r, 180.0, 180.0 + 180.0 * s)
            b.arc(c, r, 180.0 + 180.0 * s, 180.0 + 360.0 * s)
            b.close()
            return b.build()
        }

        /** Rounded rectangle with corner radius [r]. */
        fun rrect(x: Double, y: Double, w: Double, h: Double, r: Double): Outline {
            val b = OutlineBuilder()
            b.move(x + r, y)
            b.line(x + w - r, y)
            b.arc(Pt(x + w - r, y + r), r, -90.0, 0.0)
            b.line(x + w, y + h - r)
            b.arc(Pt(x + w - r, y + h - r), r, 0.0, 90.0)
            b.line(x + r, y + h)
            b.arc(Pt(x + r, y + h - r), r, 90.0, 180.0)
            b.line(x, y + r)
            b.arc(Pt(x + r, y + r), r, 180.0, 270.0)
            b.close()
            return b.build()
        }

        /** A stroked arc with round caps as a filled outline: radius [rad], stroke [w], from [a0] to [a1] degrees. */
        fun arcShape(cx: Double, cy: Double, rad: Double, w: Double, a0: Double, a1: Double): Outline {
            val b = OutlineBuilder()
            val h = w / 2
            val c = Pt(cx, cy)
            fun p(a: Double, r: Double) = Pt(cx + r * cos(a * PI / 180), cy + r * sin(a * PI / 180))
            val start = p(a0, rad + h)
            b.move(start.x, start.y)
            b.arc(c, rad + h, a0, a1)
            b.arc(p(a1, rad), h, a1, a1 + 180)
            b.arc(c, rad - h, a1, a0)
            b.arc(p(a0, rad), h, a0 + 180, a0 + 360)
            b.close()
            return b.build()
        }

        /** Absolute M, L, C and Z path data (the only commands the glyph outlines use). */
        fun parse(d: String): Outline {
            val b = OutlineBuilder()
            var cmd = 'M'
            val nums = ArrayList<Double>()
            val buf = StringBuilder()

            fun flushNumber() {
                if (buf.isNotEmpty()) {
                    nums.add(buf.toString().toDoubleOrNull() ?: 0.0)
                    buf.clear()
                }
            }

            fun apply() {
                when (cmd) {
                    'M', 'L' -> {
                        var i = 0
                        while (i + 1 < nums.size) {
                            if (cmd == 'M' && i == 0) b.move(nums[i], nums[i + 1]) else b.line(nums[i], nums[i + 1])
                            i += 2
                        }
                    }
                    'C' -> {
                        var i = 0
                        while (i + 5 < nums.size) {
                            b.cubic(Pt(nums[i], nums[i + 1]), Pt(nums[i + 2], nums[i + 3]), Pt(nums[i + 4], nums[i + 5]))
                            i += 6
                        }
                    }
                    'Z' -> b.close()
                }
                nums.clear()
            }

            for (ch in d) {
                when {
                    ch.isLetter() -> { flushNumber(); apply(); cmd = ch }
                    ch == ' ' || ch == ',' -> flushNumber()
                    else -> buf.append(ch)
                }
            }
            flushNumber()
            apply()
            return b.build()
        }

        /** [count] points spaced evenly by arc length along the closed polyline, starting at its first point. */
        fun resample(pts: List<Pt>, count: Int): List<Pt> {
            val n = pts.size
            val cum = DoubleArray(n + 1)
            for (i in 1..n) {
                val a = pts[i - 1]
                val b = pts[i % n]
                cum[i] = cum[i - 1] + hypot(b.x - a.x, b.y - a.y)
            }
            val total = cum[n]
            val out = ArrayList<Pt>(count)
            var seg = 0
            for (k in 0 until count) {
                val target = total * k / count
                while (seg < n - 1 && cum[seg + 1] < target) seg++
                val a = pts[seg]
                val b = pts[(seg + 1) % n]
                val len = cum[seg + 1] - cum[seg]
                val f = if (len > 0) (target - cum[seg]) / len else 0.0
                out.add(Pt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
            }
            return out
        }
    }
}

/** Builds an [Outline] command by command. Arcs are split into quarter turns, as in the Swift code. */
internal class OutlineBuilder {
    private val cmds = ArrayList<Cmd>()

    fun move(x: Double, y: Double) { cmds.add(Cmd.Move(Pt(x, y))) }
    fun line(x: Double, y: Double) { cmds.add(Cmd.Line(Pt(x, y))) }
    fun cubic(c1: Pt, c2: Pt, p: Pt) { cmds.add(Cmd.Cubic(c1, c2, p)) }
    fun close() { cmds.add(Cmd.Close) }

    /** Circular arc starting at the current point. Degrees, y down: a positive sweep runs clockwise on screen. */
    fun arc(c: Pt, r: Double, a0: Double, a1: Double) {
        val n = maxOf(1, ceil(abs(a1 - a0) / 90).toInt())
        val step = (a1 - a0) / n
        for (i in 0 until n) {
            val t0 = (a0 + step * i) * PI / 180
            val t1 = (a0 + step * (i + 1)) * PI / 180
            val k = 4.0 / 3.0 * tan((t1 - t0) / 4) * r
            val p1 = Pt(c.x + r * cos(t1), c.y + r * sin(t1))
            cubic(
                Pt(c.x + r * cos(t0) - k * sin(t0), c.y + r * sin(t0) + k * cos(t0)),
                Pt(p1.x + k * sin(t1), p1.y - k * cos(t1)),
                p1,
            )
        }
    }

    fun build(): Outline = Outline(cmds.toList())
}

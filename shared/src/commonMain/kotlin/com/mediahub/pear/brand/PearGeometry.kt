package com.mediahub.pear.brand

import kotlin.math.pow

// The morph maths for the "pear." face animation (Batch brand). Spec: `PearGeometry` in Core/PearGeometry.swift.
// Every shape is an outline resampled to 128 points by arc length. A morph moves each point of shape A to the matching
// point of shape B, with the start offsets aligned so outlines don't twist. Held poses are drawn from the exact curves.
// Pure Kotlin: no Compose here, so the timings and frames are unit tested.

/** The three timelines (`PearMode` in Swift). */
enum class PearMode {
    /** Full cycle: monogram, face, wink, "pear.", back to the monogram. */
    Loop,

    /** Plays once and rests on "pear.". */
    Intro,

    /** Monogram, face, wink, monogram. Loops until the caller removes it (buffering). */
    Loader,
}

/** One filled shape of a frame: a morphing polygon, or exact curves for a held pose. */
internal sealed interface PearShape {
    class Poly(val pts: List<Pt>) : PearShape

    /** Exact curves of a held pose. Filled together, nonzero, as one path. */
    class Curves(val outlines: List<Outline>) : PearShape
}

/** What to draw at one moment: the tile's opacity, then the cream and accent shapes. */
internal class PearFrame(val tile: Double, val cream: List<PearShape>, val accent: List<PearShape>)

internal object PearGeometry {
    const val CANVAS_WIDTH = 380.0
    const val CANVAS_HEIGHT = 240.0
    private const val SAMPLES = 128

    /** Tile opacity per pose: visible behind the monogram and face, gone for the wordmark. */
    private val tileOpacity = doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0)

    /** The rounded tile behind the monogram. */
    val tile: Outline = Outline.rrect(130.0, 60.0, 120.0, 120.0, 28.8)

    /** Pose graph: which poses are morphed between (0 monogram, 1 face, 2 wink, 3 wordmark). */
    private val edges = listOf(0 to 1, 1 to 2, 1 to 3, 3 to 0)

    private val tracks: List<Track>
    private val exactCream: List<List<Outline>>
    private val exactAccent: List<List<Outline>>

    init {
        val built = build()
        tracks = built.first
        exactCream = built.second
        exactAccent = built.third
    }

    // MARK: Public

    /** Frame at [ms] since the start (already multiplied by the speed). */
    fun frame(ms: Double, mode: PearMode): PearFrame {
        val seq = script(mode)
        var t = maxOf(ms, 0.0)
        if (seq.loops) {
            t %= seq.total
        } else if (t >= seq.total) {
            return held(seq.end ?: 0)
        }
        var i = 0
        while (i < seq.steps.size - 1 && t > seq.steps[i].ms) {
            t -= seq.steps[i].ms
            i++
        }
        val step = seq.steps[i]
        return when (val kind = step.kind) {
            is Kind.Hold -> held(kind.pose)
            is Kind.Morph -> morph(kind.a, kind.b, (t / step.ms).coerceIn(0.0, 1.0))
        }
    }

    /** The pose shown when motion is reduced. */
    fun restFrame(mode: PearMode): PearFrame = held(script(mode).end ?: 0)

    /** Length in ms of a one-shot mode, null for modes that loop. Before the speed multiplier. */
    fun duration(mode: PearMode): Double? {
        val s = script(mode)
        return if (s.loops) null else s.total
    }

    // MARK: Frames

    private fun held(pose: Int) = PearFrame(
        tile = tileOpacity[pose],
        cream = listOf(PearShape.Curves(exactCream[pose])),
        accent = listOf(PearShape.Curves(exactAccent[pose])),
    )

    private fun morph(a: Int, b: Int, u: Double): PearFrame {
        val cream = ArrayList<PearShape>()
        val accent = ArrayList<PearShape>()
        val key = a * 10 + b
        for (tr in tracks) {
            val pa = tr.polys[a]
            val pb = tr.polys[b]
            if (pa.isPoint && pb.isPoint) continue // zero area: nothing to draw
            val dl = tr.delay[key] ?: 0.0
            val en = tr.end[key] ?: 1.0
            val p = if (en > dl) ease(((u - dl) / (en - dl)).coerceIn(0.0, 1.0)) else if (u >= dl) 1.0 else 0.0
            val shape = PearShape.Poly(interpolate(pa.pts, pb.pts, p))
            if (tr.accent) accent.add(shape) else cream.add(shape)
        }
        return PearFrame(
            tile = tileOpacity[a] + (tileOpacity[b] - tileOpacity[a]) * ease(u),
            cream = cream,
            accent = accent,
        )
    }

    private fun interpolate(a: List<Pt>, b: List<Pt>, p: Double): List<Pt> =
        List(a.size) { j -> Pt(a[j].x + (b[j].x - a[j].x) * p, a[j].y + (b[j].y - a[j].y) * p) }

    private fun ease(u: Double): Double = if (u < 0.5) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2

    // MARK: Build

    private class Poly(var pts: List<Pt>, val isPoint: Boolean)

    private class Track(
        val accent: Boolean,
        val polys: List<Poly>,
        /** Morph key (a * 10 + b) to the fraction of the morph at which this shape starts. */
        val delay: Map<Int, Double>,
        /** Morph key to the fraction at which it lands. */
        val end: Map<Int, Double>,
    )

    private fun build(): Triple<List<Track>, List<List<Outline>>, List<List<Outline>>> {
        val tracks = ArrayList<Track>()
        val exactC = List(7) { ArrayList<Outline>() }
        val exactA = List(7) { ArrayList<Outline>() }
        for (def in definitions()) {
            val polys = ArrayList<Poly>()
            val exact = ArrayList<Outline?>()
            for ((i, spec) in def.specs.withIndex()) {
                // Identical specs share one outline, so alignment applies to every pose that uses it.
                val j = def.specs.subList(0, i).indexOf(spec)
                if (j >= 0) {
                    polys.add(polys[j])
                    exact.add(exact[j])
                    continue
                }
                val outline = spec.outline
                if (outline == null) {
                    if (spec is Spec.Point) polys.add(Poly(List(SAMPLES) { Pt(spec.x, spec.y) }, isPoint = true))
                    exact.add(null)
                    continue
                }
                var pts = Outline.resample(outline.flattened(), SAMPLES)
                val area = area(pts)
                // Outer outlines wind one way, counters the other, so nonzero fill leaves the holes open.
                if ((!def.hole && area < 0) || (def.hole && area > 0)) pts = pts.reversed()
                polys.add(Poly(pts, isPoint = false))
                exact.add(outline)
            }
            align(polys)
            tracks.add(Track(def.accent, polys, def.delay, def.end))
            for (i in 0 until 7) {
                val p = exact[i] ?: continue
                if (def.accent) exactA[i].add(p) else exactC[i].add(p)
            }
        }
        return Triple(tracks, exactC, exactA)
    }

    private fun area(pts: List<Pt>): Double {
        var a = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2
    }

    /** Rotates each outline's start point so it lines up with its neighbours in the pose graph. */
    private fun align(polys: List<Poly>) {
        val m = SAMPLES
        repeat(3) {
            for (i in 1..3) {
                val poly = polys[i]
                if (poly.isPoint) continue
                val neighbours = ArrayList<List<Pt>>()
                for ((a, b) in edges) {
                    val o = if (a == i) b else if (b == i) a else -1
                    if (o >= 0 && !polys[o].isPoint && polys[o] !== poly) neighbours.add(polys[o].pts)
                }
                if (neighbours.isEmpty()) continue
                var best = Double.POSITIVE_INFINITY
                var bestShift = 0
                for (k in 0 until m) {
                    var c = 0.0
                    for (n in neighbours) {
                        var idx = k
                        for (j in 0 until m) {
                            val dx = n[j].x - poly.pts[idx].x
                            val dy = n[j].y - poly.pts[idx].y
                            c += dx * dx + dy * dy
                            idx++
                            if (idx == m) idx = 0
                        }
                    }
                    if (c < best) {
                        best = c
                        bestShift = k
                    }
                }
                if (bestShift != 0) poly.pts = poly.pts.subList(bestShift, m) + poly.pts.subList(0, bestShift)
            }
        }
    }

    // MARK: Shapes

    private sealed interface Spec {
        val outline: Outline?

        data class Point(val x: Double, val y: Double) : Spec {
            override val outline: Outline? get() = null
        }

        data class Circle(val x: Double, val y: Double, val r: Double, val ccw: Boolean) : Spec {
            override val outline: Outline? get() = Outline.circle(x, y, r, ccw)
        }

        data class Rrect(val x: Double, val y: Double, val w: Double, val h: Double, val r: Double) : Spec {
            override val outline: Outline? get() = Outline.rrect(x, y, w, h, r)
        }

        /** cx, cy, R, width, from degrees, to degrees. */
        data class Arc(val cx: Double, val cy: Double, val rad: Double, val w: Double, val a0: Double, val a1: Double) : Spec {
            override val outline: Outline? get() = Outline.arcShape(cx, cy, rad, w, a0, a1)
        }

        data class Glyph(val d: String) : Spec {
            override val outline: Outline? get() = Outline.parse(d)
        }
    }

    private class Def(
        val accent: Boolean,
        val hole: Boolean,
        val specs: List<Spec>,
        val delay: Map<Int, Double> = emptyMap(),
        val end: Map<Int, Double> = emptyMap(),
    )

    private fun key(a: Int, b: Int) = a * 10 + b

    /** One outline per pose: [monogram, face, wink, wordmark], then variants for the transitions back to the monogram. */
    private fun poses(
        l: Spec, f: Spec, w: Spec, d: Spec,
        p4: Spec? = null, p5: Spec? = null, p6: Spec? = null,
    ): List<Spec> = listOf(l, f, w, d, p4 ?: l, p5 ?: d, p6 ?: f)

    private fun definitions(): List<Def> {
        val a = Spec.Point(191.6, 111.2)
        val b = Spec.Point(94.6, 119.6)
        val eyeL = Spec.Rrect(164.0, 91.0, 16.0, 28.0, 8.0)
        val eyeR = Spec.Rrect(200.0, 91.0, 16.0, 28.0, 8.0)
        val smile = Spec.Arc(190.0, 103.0, 38.0, 9.0, 45.0, 135.0)
        val wink = Spec.Arc(208.0, 121.0, 15.5, 8.0, 230.0, 310.0)
        val eOc = Spec.Point(156.4, 119.6)
        val eHc = Spec.Point(157.0, 106.3)
        val aOc = Spec.Point(215.1, 119.6)
        val aHc = Spec.Point(214.9, 130.1)
        val rOc = Spec.Point(272.9, 118.9)
        return listOf(
            // P stem -> left eye -> (collapses into the word)
            Def(
                accent = false, hole = false,
                specs = poses(Spec.Rrect(158.0, 82.4, 20.8, 75.2, 10.4), eyeL, eyeL, Spec.Point(72.6, 160.0)),
            ),
            // P bowl -> right eye / winking eye -> p
            Def(
                accent = false, hole = false,
                specs = poses(Spec.Circle(191.6, 111.2, 30.4, ccw = false), eyeR, wink, Spec.Glyph(PearGlyphs.pO)),
                delay = mapOf(key(0, 1) to 0.15, key(5, 4) to 0.1),
            ),
            // counter of the P -> p counter
            Def(
                accent = false, hole = true,
                specs = poses(Spec.Circle(191.6, 111.2, 12.6, ccw = true), a, a, Spec.Glyph(PearGlyphs.pH), p4 = a, p5 = b, p6 = b),
                delay = mapOf(key(6, 3) to 0.55, key(1, 0) to 0.55),
                end = mapOf(key(0, 1) to 0.45),
            ),
            Def(
                accent = false, hole = false,
                specs = poses(eOc, eOc, eOc, Spec.Glyph(PearGlyphs.eO)),
                delay = mapOf(key(6, 3) to 0.1),
            ),
            Def(
                accent = false, hole = true,
                specs = poses(eHc, eHc, eHc, Spec.Glyph(PearGlyphs.eH), p5 = eHc),
                delay = mapOf(key(6, 3) to 0.6),
            ),
            Def(
                accent = false, hole = false,
                specs = poses(aOc, aOc, aOc, Spec.Glyph(PearGlyphs.aO)),
                delay = mapOf(key(6, 3) to 0.2, key(5, 4) to 0.1),
            ),
            Def(
                accent = false, hole = true,
                specs = poses(aHc, aHc, aHc, Spec.Glyph(PearGlyphs.aH), p5 = aHc),
                delay = mapOf(key(6, 3) to 0.65),
            ),
            Def(
                accent = false, hole = false,
                specs = poses(rOc, rOc, rOc, Spec.Glyph(PearGlyphs.rO)),
                delay = mapOf(key(6, 3) to 0.3, key(5, 4) to 0.2),
            ),
            // accent dot -> smile -> period
            Def(
                accent = true, hole = false,
                specs = poses(Spec.Circle(191.6, 111.2, 13.6, ccw = false), smile, smile, Spec.Glyph(PearGlyphs.dotPath)),
                delay = mapOf(key(0, 1) to 0.05, key(1, 0) to 0.05, key(6, 3) to 0.1, key(5, 4) to 0.2),
            ),
        )
    }

    // MARK: Timelines (ms, before the speed multiplier)

    private sealed interface Kind {
        data class Hold(val pose: Int) : Kind
        data class Morph(val a: Int, val b: Int) : Kind
    }

    private class Step(val kind: Kind, val ms: Double)

    private class Script(val steps: List<Step>, val loops: Boolean, val end: Int?) {
        val total: Double = steps.sumOf { it.ms }
    }

    private fun hold(s: Int, ms: Double) = Step(Kind.Hold(s), ms)
    private fun move(a: Int, b: Int, ms: Double) = Step(Kind.Morph(a, b), ms)

    private fun script(mode: PearMode): Script = when (mode) {
        PearMode.Loop -> Script(
            steps = listOf(
                hold(0, 1000.0), move(0, 1, 900.0), hold(1, 700.0), move(1, 2, 240.0), hold(2, 380.0),
                move(2, 1, 240.0), hold(1, 350.0), move(6, 3, 1000.0), hold(3, 1500.0), move(3, 5, 240.0),
                move(5, 4, 1000.0), move(4, 0, 240.0),
            ),
            loops = true,
            end = null,
        )
        PearMode.Intro -> Script(
            steps = listOf(
                hold(0, 600.0), move(0, 1, 800.0), hold(1, 500.0), move(1, 2, 220.0), hold(2, 300.0),
                move(2, 1, 220.0), hold(1, 300.0), move(6, 3, 1000.0),
            ),
            loops = false,
            end = 3,
        )
        PearMode.Loader -> Script(
            steps = listOf(
                hold(0, 200.0), move(0, 1, 700.0), hold(1, 300.0), move(1, 2, 240.0), hold(2, 300.0),
                move(2, 1, 240.0), hold(1, 200.0), move(1, 0, 700.0),
            ),
            loops = true,
            end = null,
        )
    }
}

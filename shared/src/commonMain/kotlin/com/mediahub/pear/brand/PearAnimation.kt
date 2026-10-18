package com.mediahub.pear.brand

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.shell.PlatformCaps
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

// Drawing for the "pear." face animation and the wordmark (Batch brand). Spec: `PearAnimationView` and `PearWordmark`
// in the Swift app. The geometry is in PearGeometry.kt and PearOutline.kt; this file only turns it into Compose paths.

/**
 * The "pear." face animation. [PearMode.Intro] plays once and rests on the wordmark, then calls [onFinish]; the loader
 * loops. Time comes from the frame clock, so a busy frame skips poses instead of slowing the animation. [speed] is
 * the multiplier (the Swift app runs at 2x).
 */
@Composable
fun PearAnimation(
    mode: PearMode,
    modifier: Modifier = Modifier,
    speed: Double = 2.0,
    onFinish: (() -> Unit)? = null,
) {
    val total = remember(mode) { PearGeometry.duration(mode) }
    // Read only inside the draw lambda, so each frame redraws the canvas and nothing else.
    var elapsed by remember(mode) { mutableStateOf(0.0) }

    LaunchedEffect(mode) {
        var start = -1L
        while (true) {
            withFrameMillis { now ->
                if (start < 0) start = now
                elapsed = (now - start) * speed
            }
            if (total != null && elapsed >= total) break
        }
    }
    val finished = onFinish
    LaunchedEffect(mode) {
        if (total != null && finished != null) {
            // Wall-clock length of the one-shot timeline at this speed.
            delay((total / speed).toLong())
            finished()
        }
    }

    Canvas(modifier.aspectRatio((PearGeometry.CANVAS_WIDTH / PearGeometry.CANVAS_HEIGHT).toFloat())) {
        val frame = PearGeometry.frame(elapsed, mode)
        // Fit the 380 x 240 canvas inside the box and centre it.
        val s = min(size.width / PearGeometry.CANVAS_WIDTH, size.height / PearGeometry.CANVAS_HEIGHT)
        val dx = (size.width - PearGeometry.CANVAS_WIDTH * s) / 2
        val dy = (size.height - PearGeometry.CANVAS_HEIGHT * s) / 2
        val map: (Pt) -> Pt = { p -> Pt(dx + p.x * s, dy + p.y * s) }

        if (frame.tile > 0.001) {
            drawPath(outlinePath(PearGeometry.tile, map), PearBrand.tile.copy(alpha = frame.tile.toFloat()))
        }
        drawPath(shapesPath(frame.cream, map), PearBrand.cream)
        drawPath(shapesPath(frame.accent, map), PearBrand.accent)
    }
}

/**
 * The typed "pear." logo, drawn as vectors (no images). Ink follows the appearance (cream on dark, near-black on light);
 * the full stop is always the brand violet. [height] sets the size; the width follows the wordmark's proportions.
 */
@Composable
fun PearWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    color: Color? = null,
) {
    val box = PearGlyphs.box
    val ink = color ?: if (PearTheme.colors.isDark) PearBrand.cream else PearBrand.ink
    val width = height * (box.width / box.height).toFloat()
    Canvas(modifier.size(width = width, height = height)) {
        // Scale the glyph box to fill the canvas exactly, as FitShape does in Swift.
        val sx = size.width / box.width
        val sy = size.height / box.height
        val map: (Pt) -> Pt = { p -> Pt((p.x - box.x) * sx, (p.y - box.y) * sy) }
        val letters = Path().apply { fillType = PathFillType.NonZero }
        PearGlyphs.letters.forEach { addOutline(letters, it, map) }
        drawPath(letters, ink)
        drawPath(outlinePath(PearGlyphs.dot, map), PearBrand.accent)
    }
}

/** How long the intro animation runs on desktop, in milliseconds. */
private const val DESKTOP_INTRO_MILLIS = 3_000.0

/**
 * The launch screen: the intro on the splash colour, on top of the app while it loads. The intro runs at 2x on touch
 * devices (about 2 s) and is slowed to 3 s on desktop. Then a 350 ms beat and a 350 ms fade (`PearLaunchScreen` in the
 * Swift app uses the same beat and fade).
 */
@Composable
fun PearLaunchOverlay() {
    var showing by remember { mutableStateOf(true) }
    var dismissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Builds the geometry off the UI thread, so the first frame of the intro doesn't stall.
        withContext(Dispatchers.Default) { PearGeometry.duration(PearMode.Intro) }
    }
    LaunchedEffect(dismissing) {
        if (dismissing) {
            delay(350)
            showing = false
        }
    }
    AnimatedVisibility(visible = showing, exit = fadeOut(tween(350))) {
        // Touch devices keep the normal 2x speed (~2 s). Desktop plays the intro in 3 s.
        val introSpeed = if (PlatformCaps.isTouchFirst) 2.0 else (PearGeometry.duration(PearMode.Intro) ?: 3940.0) / DESKTOP_INTRO_MILLIS
        Box(
            Modifier.fillMaxSize().background(PearBrand.splash)
                // Swallows clicks, so the app underneath can't be used while the intro plays.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            PearAnimation(
                mode = PearMode.Intro,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 28.dp),
                speed = introSpeed,
                onFinish = { dismissing = true },
            )
        }
    }
}

// MARK: Paths

private fun outlinePath(outline: Outline, map: (Pt) -> Pt): Path =
    Path().apply { fillType = PathFillType.NonZero }.also { addOutline(it, outline, map) }

private fun shapesPath(shapes: List<PearShape>, map: (Pt) -> Pt): Path {
    val path = Path().apply { fillType = PathFillType.NonZero }
    for (shape in shapes) {
        when (shape) {
            is PearShape.Poly -> {
                shape.pts.forEachIndexed { i, p ->
                    val q = map(p)
                    if (i == 0) path.moveTo(q.x.toFloat(), q.y.toFloat()) else path.lineTo(q.x.toFloat(), q.y.toFloat())
                }
                path.close()
            }
            is PearShape.Curves -> shape.outlines.forEach { addOutline(path, it, map) }
        }
    }
    return path
}

private fun addOutline(path: Path, outline: Outline, map: (Pt) -> Pt) {
    for (c in outline.cmds) {
        when (c) {
            is Cmd.Move -> map(c.p).let { path.moveTo(it.x.toFloat(), it.y.toFloat()) }
            is Cmd.Line -> map(c.p).let { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
            is Cmd.Cubic -> {
                val a = map(c.c1)
                val b = map(c.c2)
                val e = map(c.p)
                path.cubicTo(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), e.x.toFloat(), e.y.toFloat())
            }
            Cmd.Close -> path.close()
        }
    }
}

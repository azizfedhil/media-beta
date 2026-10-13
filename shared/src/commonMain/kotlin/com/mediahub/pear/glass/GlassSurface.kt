package com.mediahub.pear.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearColors
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import androidx.compose.foundation.border

internal val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }
internal val LocalGlassStore = staticCompositionLocalOf { GlassStore() }

/**
 * A scene with glass: [background] is captured as the blur source, [content] is drawn above it and its
 * glass surfaces blur whatever is behind them. For video, put the video surface in [background].
 */
@Composable
fun GlassScene(
    store: GlassStore,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassState provides state, LocalGlassStore provides store) {
        Box(modifier) {
            Box(Modifier.fillMaxSize().hazeSource(state), content = background)
            content()
        }
    }
}

private fun frostedStyle(c: PearColors, wash: Color?): HazeBlurStyle {
    val base = c.surface.copy(alpha = if (c.isDark) 0.50f else 0.55f)
    val effects = if (wash == null) listOf(HazeColorEffect.tint(base)) else listOf(HazeColorEffect.tint(base), HazeColorEffect.tint(wash))
    return HazeBlurStyle {
        blurRadius(20.dp)
        noiseFactor(0.12f)
        colorEffects(effects)
        // Used by Haze when blur is unavailable on a platform.
        fallbackColorEffect(HazeColorEffect.tint(c.surface.copy(alpha = 0.85f)))
    }
}

private fun flatColor(c: PearColors, wash: Color?): Color {
    val base = c.surface.copy(alpha = 0.86f)
    return if (wash == null) base else wash.compositeOver(base)
}

/**
 * The base of every glass component, and the only place glass is drawn, so every glass item has the same clarity. It
 * draws the style [GlassStore.mode] picks (blur, glass, or a flat tint), clipped to [shape]. Outside a [GlassScene] it
 * falls back to flat.
 *
 * There is no wash and no outline, as on iOS Liquid Glass. The one exception is [wash]: the accent at 55% on a selected
 * or active control. [highlight] is the hover fill, which fades in and out rather than snapping.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    wash: Color? = null,
    highlight: Boolean = false,
    /** A hairline rim in this colour, 0.5 dp wide. Only the player asks for one; glass has none otherwise. */
    rim: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = PearTheme.colors
    val state = LocalGlassState.current
    val store = LocalGlassStore.current
    // Glass everywhere, except on low battery, where it falls back to flat (see PowerStatus).
    val mode = if (state == null || PowerStatus.lowBattery) GlassMode.Flat else store.mode
    val perf = store.performance.haze
    // The hover fill fades in and out. It is read in the draw phase, so the glass itself does not recompose as it moves.
    val lit by animateFloatAsState(if (highlight) 1f else 0f, PearMotion.snappy<Float>(PearMotion.FAST))
    val hover = c.fillSubtle

    val effect: Modifier = when (mode) {
        GlassMode.Frosted -> {
            val style = remember(c.isDark, c.surface, wash) { frostedStyle(c, wash) }
            Modifier.clip(shape).hazeBlur(input = HazeInput.Sources(state!!), style = style, performanceMode = perf)
        }
        GlassMode.Glass, GlassMode.Clear -> {
            val style = remember(mode, shape, wash) {
                val base = if (mode == GlassMode.Clear) GlassStyle.clear else GlassStyle.regular
                base.then {
                    shape(shape)
                    if (wash != null) tint(wash)
                }
            }
            Modifier.hazeGlass(input = HazeInput.Sources(state!!), style = style, performanceMode = perf).clip(shape)
        }
        GlassMode.Flat -> Modifier.clip(shape).background(flatColor(c, wash))
    }
    Box(modifier.then(effect).then(if (rim != null) Modifier.border(0.5f.dp, rim, shape) else Modifier).drawBehind { if (lit > 0f) drawRect(hover.copy(alpha = hover.alpha * lit)) }) {
        content()
    }
}

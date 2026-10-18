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

internal val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** True inside a glass surface. A surface drawn inside one is flat, so glass never sits in glass (`glass.md`). */
internal val LocalInsideGlass = staticCompositionLocalOf { false }

/** True inside the player: Flat stays see-through there. Everywhere else Flat is solid. */
internal val LocalFlatSeeThrough = staticCompositionLocalOf { false }

/**
 * The style a surface draws: flat when there is no glass scene, on low battery, or when it sits inside another glass surface
 * ([nested]); otherwise the style the store chose. Pure, so it is unit tested.
 */
internal fun effectiveGlassMode(hasScene: Boolean, lowBattery: Boolean, nested: Boolean, mode: GlassMode): GlassMode =
    if (!hasScene || lowBattery || nested) GlassMode.Flat else mode
internal val LocalGlassStore = staticCompositionLocalOf { GlassStore() }

/**
 * Whether the sidebar is drawn as glass here, by the same rule [Sidebar] uses. A page only draws behind the sidebar when
 * this is true, so the page and the sidebar never disagree.
 */
@Composable
internal fun rememberSidebarGlass(): Boolean =
    effectiveGlassMode(
        hasScene = LocalGlassState.current != null,
        lowBattery = PowerStatus.lowBattery,
        nested = false,
        mode = LocalGlassStore.current.sidebarMode,
    ) != GlassMode.Flat

/**
 * A scene with glass: [background] is captured as the blur source, [content] is drawn above it and its
 * glass surfaces blur whatever is behind them. For video, put the video surface in [background].
 */
@Composable
fun GlassScene(
    store: GlassStore,
    modifier: Modifier = Modifier,
    flatSeeThrough: Boolean = false,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassState provides state, LocalGlassStore provides store, LocalFlatSeeThrough provides flatSeeThrough) {
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

private fun flatColor(c: PearColors, wash: Color?, seeThrough: Boolean): Color {
    val base = c.surface.copy(alpha = if (seeThrough) 0.86f else 1f)
    return if (wash == null) base else wash.compositeOver(base)
}

/**
 * The base of every glass component, and the only place glass is drawn, so every glass item has the same clarity. It
 * draws the style [modeOverride] picks, or else [GlassStore.mode] (blur, glass, or a flat tint), clipped to [shape].
 * Outside a [GlassScene] it falls back to flat.
 *
 * There is no wash and no outline, as on iOS Liquid Glass. The one exception is [wash]: the accent at 55% on a selected
 * or active control. [highlight] is the hover fill, which fades in and out rather than snapping.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(PearRadii.card),
    wash: Color? = null,
    /** A neutral lift under the glass (a white veil), used when there is no [wash]. Glass without a veil is clearer. */
    veil: Color? = null,
    highlight: Boolean = false,
    /** Draws this surface in the given style instead of the store's [GlassStore.mode]. Null: the store's mode. */
    modeOverride: GlassMode? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = PearTheme.colors
    val state = LocalGlassState.current
    val store = LocalGlassStore.current
    // Glass everywhere, except on low battery, where it falls back to flat (see PowerStatus).
    val nested = LocalInsideGlass.current
    val seeThrough = LocalFlatSeeThrough.current
    if (nested && GlassTuning.NESTING_WARNINGS) println("Pear: a glass surface sits inside another glass surface, so it is drawn flat.")
    val mode = effectiveGlassMode(hasScene = state != null, lowBattery = PowerStatus.lowBattery, nested = nested, mode = modeOverride ?: store.mode)
    val perf = store.performance.haze
    // The hover fill fades in and out. It is read in the draw phase, so the glass itself does not recompose as it moves.
    val lit by animateFloatAsState(if (highlight) 1f else 0f, PearMotion.snappy<Float>(PearMotion.FAST))
    val hover = c.fillSubtle

    // The tint under the glass: the accent wash over the veil when both are set; otherwise whichever is set.
    val tintColor: Color? = when {
        wash != null && veil != null -> wash.compositeOver(veil)
        else -> wash ?: veil
    }

    val effect: Modifier = when (mode) {
        GlassMode.Frosted -> {
            val style = remember(c.isDark, c.surface, tintColor) { frostedStyle(c, tintColor) }
            Modifier.clip(shape).hazeBlur(input = HazeInput.Sources(state!!), style = style, performanceMode = perf)
        }
        GlassMode.Glass, GlassMode.Clear -> {
            val style = remember(mode, shape, tintColor) {
                val base = if (mode == GlassMode.Clear) GlassStyle.clear else GlassStyle.regular
                base.then {
                    shape(shape)
                    if (tintColor != null) tint(tintColor)
                }
            }
            Modifier.hazeGlass(input = HazeInput.Sources(state!!), style = style, performanceMode = perf).clip(shape)
        }
        // Inside another glass surface the flat fill is the plain card tint, so the nested item reads as a flat control.
        GlassMode.Flat -> Modifier.clip(shape).background(if (nested) (tintColor?.compositeOver(c.fill) ?: c.fill) else flatColor(c, tintColor, seeThrough))
    }
    Box(modifier.then(effect).drawBehind { if (lit > 0f) drawRect(hover.copy(alpha = hover.alpha * lit)) }) {
        CompositionLocalProvider(LocalInsideGlass provides true) { content() }
    }
}

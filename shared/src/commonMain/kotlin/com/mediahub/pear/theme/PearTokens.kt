package com.mediahub.pear.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii, taken from the values the Swift app uses most (12, 14, 16, 22, 28). */
object PearRadii {
    val xs: Dp = 6.dp
    val sm: Dp = 12.dp // small rows, chips, menu items
    val md: Dp = 14.dp // sidebar rows
    val lg: Dp = 16.dp // menus, grouped lists
    val card: Dp = 22.dp // cards, bars, posters
    val sheet: Dp = 28.dp // top corners of sheets
    val pill: Dp = 999.dp // capsule (any value above half the height)
}

object PearSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Animation durations (ms) and curves. */
object PearMotion {
    const val FAST = 150 // press and hover feedback
    const val BASE = 250 // fades, small moves
    const val SLOW = 400 // large surfaces
    const val PUSH = 350 // page push / pop

    /** Quick start, long soft landing: close to how iOS eases UI moves. */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The iOS navigation-push curve. */
    val push = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    fun <T> snappy(durationMs: Int = BASE): FiniteAnimationSpec<T> = tween(durationMs, easing = standard)

    fun <T> pushSpec(): FiniteAnimationSpec<T> = tween(PUSH, easing = push)
}

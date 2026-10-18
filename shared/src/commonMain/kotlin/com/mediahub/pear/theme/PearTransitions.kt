package com.mediahub.pear.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/**
 * Enter and leave motion for everything that appears over the app, so the whole desktop app moves the same way. Built
 * from [PearMotion]: fades use its durations, and the panels spring in. Leaving is always a little quicker than arriving.
 */
object PearTransitions {
    /** The dim behind an overlay. It only fades, so the scrim never moves under the panel. */
    val overlayScrimIn: EnterTransition = fadeIn(tween(PearMotion.BASE))
    val overlayScrimOut: ExitTransition = fadeOut(tween(PearMotion.FAST))

    /** A centred panel (dialog, menu-sized card): fades in while it springs up from 90%, and eases out to 96%. */
    val dialogIn: EnterTransition =
        fadeIn(tween(PearMotion.BASE)) +
            scaleIn(spring(dampingRatio = 0.82f, stiffness = 420f), initialScale = 0.9f)
    val dialogOut: ExitTransition =
        fadeOut(tween(PearMotion.FAST)) +
            scaleOut(tween(PearMotion.FAST, easing = FastOutLinearInEasing), targetScale = 0.96f)

    /** A bottom sheet: springs up from below, and drops back on a short ease-in. */
    val sheetIn: EnterTransition =
        slideInVertically(spring(dampingRatio = 0.88f, stiffness = 380f)) { it } +
            fadeIn(tween(PearMotion.FAST))
    val sheetOut: ExitTransition =
        slideOutVertically(tween(240, easing = FastOutLinearInEasing)) { it } +
            fadeOut(tween(240))

    /** A player panel: a short rise and a fade. */
    val panelIn: EnterTransition =
        fadeIn(tween(PearMotion.BASE, easing = PearMotion.standard)) +
            slideInVertically(animationSpec = tween(PearMotion.BASE, easing = PearMotion.standard)) { it / 16 }
    /**
     * The panel's exit: it shrinks and drops a little, with no fade. Haze glass does not reliably follow a fading ancestor,
     * so a faded card would leave its blur behind as a rectangle. This way the card keeps its full opacity while it leaves.
     */
    val panelOut: ExitTransition =
        scaleOut(tween(PearMotion.FAST, easing = FastOutLinearInEasing), targetScale = 0.97f) +
            slideOutVertically(tween(PearMotion.FAST, easing = FastOutLinearInEasing)) { it / 24 }
}

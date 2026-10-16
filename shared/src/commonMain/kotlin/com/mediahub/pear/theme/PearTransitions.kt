package com.mediahub.pear.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/**
 * Enter and leave motion for everything that appears over the app, so the whole desktop app moves the same way. Built
 * from [PearMotion]: the standard curve for fades, the iOS push curve for sheets. Leaving is always a little quicker
 * than arriving.
 */
object PearTransitions {
    /** A floating panel or menu: fades in while it grows from 96%, and shrinks out the same way. */
    val overlayIn: EnterTransition =
        fadeIn(tween(PearMotion.BASE, easing = PearMotion.standard)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(PearMotion.BASE, easing = PearMotion.standard))
    val overlayOut: ExitTransition =
        fadeOut(tween(PearMotion.FAST, easing = PearMotion.standard)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(PearMotion.FAST, easing = PearMotion.standard))

    /** A bottom sheet (phone layout): rises from below on the iOS push curve, and drops back the same way. */
    val sheetIn: EnterTransition =
        fadeIn(tween(PearMotion.BASE, easing = PearMotion.standard)) +
            slideInVertically(animationSpec = tween(PearMotion.PUSH, easing = PearMotion.push)) { it / 5 }
    val sheetOut: ExitTransition =
        fadeOut(tween(PearMotion.FAST, easing = PearMotion.standard)) +
            slideOutVertically(animationSpec = tween(PearMotion.FAST, easing = PearMotion.standard)) { it / 5 }

    /** A player panel: a short rise and a fade. */
    val panelIn: EnterTransition =
        fadeIn(tween(PearMotion.BASE, easing = PearMotion.standard)) +
            slideInVertically(animationSpec = tween(PearMotion.BASE, easing = PearMotion.standard)) { it / 16 }
    val panelOut: ExitTransition = fadeOut(tween(PearMotion.FAST, easing = PearMotion.standard))
}

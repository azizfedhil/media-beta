package com.mediahub.pear.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.brand.PearWordmark
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/**
 * The wide frame, for a window of 640 dp and more. From 900 dp a step rail sits on the left with the brand, the step's title
 * and the steps; below that a slim header carries the brand and progress. The form is centred on the right, with a footer
 * whose buttons sit at its right edge.
 */
@Composable
internal fun OnboardingWide(draft: OnboardingDraft, actions: OnboardingActions, showRail: Boolean) {
    val accent by animateColorAsState(PearTheme.colors.accent, tween(300))
    Box(Modifier.fillMaxSize().background(Color.Black).accentWash(accent)) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (showRail) RailPane(draft, actions, Modifier.width(340.dp).fillMaxHeight())

            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!showRail) SlimHeader(draft, actions)
                Crossfade(
                    targetState = draft.step,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    animationSpec = tween(PearMotion.BASE, easing = PearMotion.standard),
                ) { step ->
                    StepPage(
                        centered = step == OnboardingStep.Welcome || step == OnboardingStep.Done,
                        maxWidth = 720.dp,
                    ) {
                        OnboardingStepBody(step, wide = true, headerInForm = !showRail, draft = draft, actions = actions)
                    }
                }
                WideFooter(draft, actions, showSkipHere = showRail)
            }
        }
    }
}

/** The left pane: the brand, the step's title and subtitle, the steps, and a live preview of the accent at the bottom. */
@Composable
private fun RailPane(draft: OnboardingDraft, actions: OnboardingActions, modifier: Modifier) {
    Column(modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PearWordmark(height = 28.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PearText(stepTitle(draft.step), style = PearTheme.type.title2.weight(FontWeight.Bold))
            PearText(
                stepSubtitle(draft.step, draft.hasProxy),
                style = PearTheme.type.callout,
                color = PearTheme.colors.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnboardingStep.entries.forEach { step ->
                RailItem(step, current = draft.step, onJump = actions.onJump)
            }
        }
        Spacer(Modifier.weight(1f))
        PreviewCard()
    }
}

/** One step on the rail: a tick once passed (it can be clicked to go back), an accent dot for the current one, a dim dot after. */
@Composable
private fun RailItem(step: OnboardingStep, current: OnboardingStep, onJump: (OnboardingStep) -> Unit) {
    val c = PearTheme.colors
    val passed = step < current
    val isCurrent = step == current
    val tap = if (passed) {
        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = { onJump(step) })
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.md))
            .then(tap)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                passed -> PearIcon("checkmark.circle.fill", size = 20.dp, tint = c.green)
                isCurrent -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            }
        }
        PearText(
            railLabel(step),
            style = PearTheme.type.subheadline.weight(if (isCurrent) FontWeight.Bold else FontWeight.Medium),
            color = when {
                isCurrent -> c.textPrimary
                passed -> c.textSecondary
                else -> c.textTertiary
            },
        )
    }
}

/** The narrower wide layout: the brand on the left, the progress in the middle, and Skip on the right. */
@Composable
private fun SlimHeader(draft: OnboardingDraft, actions: OnboardingActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PearWordmark(height = 18.dp)
        Spacer(Modifier.weight(1f))
        ProgressDots(draft.step)
        Spacer(Modifier.weight(1f))
        if (OnboardingRules.showSkip(draft.step)) TextAction("Skip setup", onClick = actions.onSkip)
    }
}

/**
 * The footer of the form, right-aligned: Skip on the left when the rail is there (otherwise the slim header carries it),
 * then Back, then the main button.
 */
@Composable
private fun WideFooter(draft: OnboardingDraft, actions: OnboardingActions, showSkipHere: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showSkipHere && OnboardingRules.showSkip(draft.step)) TextAction("Skip setup", onClick = actions.onSkip)
        Spacer(Modifier.weight(1f))
        if (OnboardingRules.showBack(draft.step)) {
            GlassButton(onClick = actions.onBack, label = "Back", modifier = Modifier.width(110.dp))
        }
        GlassButton(
            onClick = actions.onPrimary,
            label = buttonLabel(draft),
            tint = PearTheme.colors.accent,
            enabled = draft.canContinue,
            modifier = Modifier.width(200.dp),
        )
    }
}

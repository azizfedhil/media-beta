package com.mediahub.pear.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearTheme

/**
 * The phone frame: one column. The step scrolls in the middle; the progress and the buttons stay at the bottom, where a
 * thumb reaches them. The insets and the keyboard are respected.
 */
@Composable
internal fun OnboardingPhone(draft: OnboardingDraft, actions: OnboardingActions) {
    val accent by animateColorAsState(PearTheme.colors.accent, tween(300))
    Box(Modifier.fillMaxSize().background(Color.Black).accentWash(accent)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (OnboardingRules.showSkip(draft.step)) TextAction("Skip setup", onClick = actions.onSkip)
            }

            Crossfade(
                targetState = draft.step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(PearMotion.BASE, easing = PearMotion.standard),
            ) { step ->
                StepPage(
                    centered = step == OnboardingStep.Welcome || step == OnboardingStep.Done,
                    maxWidth = 560.dp,
                ) {
                    OnboardingStepBody(step, wide = false, headerInForm = true, draft = draft, actions = actions)
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressDots(draft.step)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (OnboardingRules.showBack(draft.step)) {
                        GlassButton(onClick = actions.onBack, label = "Back", modifier = Modifier.width(110.dp))
                    }
                    GlassButton(
                        onClick = actions.onPrimary,
                        label = buttonLabel(draft),
                        tint = PearTheme.colors.accent,
                        enabled = draft.canContinue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

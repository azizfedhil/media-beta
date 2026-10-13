package com.mediahub.pear.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.openUrl
import com.mediahub.pear.shell.PearSwitch
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/** What the frames can ask of the host: moving between steps, finishing, and adding an add-on. */
internal class OnboardingActions(
    val onPrimary: () -> Unit,
    val onBack: () -> Unit,
    val onSkip: () -> Unit,
    val onJump: (OnboardingStep) -> Unit,
    val onAddAddon: () -> Unit,
)

/** The title of a step, as the frames show it. */
internal fun stepTitle(step: OnboardingStep): String = when (step) {
    OnboardingStep.Welcome -> "Welcome"
    OnboardingStep.Profile -> "Who's watching?"
    OnboardingStep.Look -> "Pick your colour"
    OnboardingStep.Content -> "What do you want to see?"
    OnboardingStep.Home -> "Build your Home"
    OnboardingStep.Keys -> "Connect your services"
    OnboardingStep.Done -> "All set"
}

/** The subtitle of a step. Keys changes when TMDB is built in. */
internal fun stepSubtitle(step: OnboardingStep, hasProxy: Boolean): String = when (step) {
    OnboardingStep.Welcome -> "Everything you watch, in one place. A minute of setup makes it yours."
    OnboardingStep.Profile -> "Sign in with Simkl to bring your name, picture and library, or just pick a name."
    OnboardingStep.Look -> "It tints buttons, progress bars and highlights across the app. Change it any time in Settings."
    OnboardingStep.Content -> "Switch off anything you don't want suggested. It is hidden from Home, Explore and recommendations."
    OnboardingStep.Home -> "Choose the rows you want, and add Stremio add-ons for more catalogues."
    OnboardingStep.Keys -> if (hasProxy) {
        "TMDB is already built in. MDBList and TheTVDB use your own free API keys, which stay on this device. " +
            "You can add or change any of them later in Settings → Integrations."
    } else {
        "Pear uses your own free API keys. They stay on this device, and you can add or change them later in Settings → Integrations."
    }
    OnboardingStep.Done -> "Everything here can be changed in Settings."
}

/** A step's name on the wide frame's rail. */
internal fun railLabel(step: OnboardingStep): String = when (step) {
    OnboardingStep.Welcome -> "Welcome"
    OnboardingStep.Profile -> "Profile"
    OnboardingStep.Look -> "Colour"
    OnboardingStep.Content -> "Content"
    OnboardingStep.Home -> "Home"
    OnboardingStep.Keys -> "Keys"
    OnboardingStep.Done -> "Finish"
}

/** The label of the main button for the draft's step. */
internal fun buttonLabel(draft: OnboardingDraft): String =
    OnboardingRules.primaryLabel(draft.step, draft.anyKeyTyped, draft.hasProxy)

/**
 * A step's page: it scrolls, its content is centred and no wider than [maxWidth]. A [centered] page (Welcome and Done)
 * also centres its content vertically.
 */
@Composable
internal fun StepPage(centered: Boolean, maxWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = if (centered) Modifier.heightIn(min = maxHeight - 16.dp) else Modifier
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = maxWidth).fillMaxWidth().then(minHeight),
                verticalArrangement = if (centered) Arrangement.Center else Arrangement.spacedBy(18.dp),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                content = content,
            )
        }
    }
}

@Composable
internal fun StepHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText(title, style = PearTheme.type.title.weight(FontWeight.Bold))
        PearText(subtitle, style = PearTheme.type.callout, color = PearTheme.colors.textSecondary)
    }
}

/** A card of the form. Its content stacks with 12 dp between items. */
@Composable
internal fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A small heading inside a card, with an optional icon in the accent colour. */
@Composable
internal fun CardLabel(text: String, symbol: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (symbol != null) PearIcon(symbol, size = 18.dp, tint = PearTheme.colors.accent)
        PearText(text, style = PearTheme.type.headline)
    }
}

@Composable
internal fun Footnote(text: String, color: Color = PearTheme.colors.textSecondary, textAlign: TextAlign? = null) {
    PearText(text, style = PearTheme.type.footnote, color = color, textAlign = textAlign)
}

/** A setting with a switch: the icon and title on the left, the detail under the title, the switch on the right. */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    symbol: String? = null,
    detail: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (symbol != null) PearIcon(symbol, size = 18.dp, tint = PearTheme.colors.accent)
        Column(Modifier.weight(1f)) {
            PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.Medium))
            if (detail != null) PearText(detail, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
        }
        PearSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A text field in the app's style. A [secret] field is masked, for API keys and PINs. It reports its focus, so the
 * setup knows when Enter belongs to the field, and Done clears the focus.
 */
@Composable
internal fun OnboardingField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val c = PearTheme.colors
    val focus = LocalFocusManager.current
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.md))
            .background(c.fill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) PearText(placeholder, style = PearTheme.type.body, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PearTheme.type.body.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

/** A plain text action (Skip setup, All, None): secondary colour, hand cursor. */
@Composable
internal fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = PearTheme.colors
    PearText(
        label,
        style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
        color = if (enabled) c.textSecondary else c.textDisabled,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, onClick = onClick),
    )
}

/** A link that opens in the browser, in the accent colour. */
@Composable
internal fun LinkText(label: String, url: String) {
    PearText(
        label,
        style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
        color = PearTheme.colors.accent,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = { openUrl(url) }),
    )
}

/** A small accent capsule: "UP NEXT", "Recommended", "Built in". */
@Composable
internal fun AccentBadge(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(PearRadii.pill))
            .background(PearTheme.colors.accent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        PearText(
            text,
            style = PearTheme.type.caption2.weight(FontWeight.Black).copy(fontSize = 10.sp),
            color = PearTheme.colors.onAccent,
        )
    }
}

/** The progress through the steps: the current one is a longer accent capsule. */
@Composable
internal fun ProgressDots(current: OnboardingStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OnboardingStep.entries.forEach { step ->
            val active = step == current
            Box(
                Modifier.width(if (active) 22.dp else 7.dp)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(if (active) PearTheme.colors.accent else Color.White.copy(alpha = 0.22f)),
            )
        }
    }
}

/** An accent colour as a round swatch, with a check mark when it is the one in use. The touch area is 48 dp, the minimum. */
@Composable
internal fun ColourSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(46.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) PearIcon("checkmark", size = 16.dp, tint = Accents.contrastingForeground(color), contentDescription = label)
        }
    }
}

/** The "no accent" swatch: half white, half black, as iOS draws it. The touch area is 48 dp. */
@Composable
internal fun SystemSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(46.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black))
            }
            if (selected) PearIcon("checkmark", size = 16.dp, tint = Color(0xFF8C8C8C), contentDescription = "System (no accent)")
        }
    }
}

/** A live sample of the accent: a Play button, an UP NEXT chip and a progress bar in the accent gradient. */
@Composable
internal fun PreviewCard(modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.card))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PearText("Preview", style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(CircleShape)
                    .background(c.accent)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PearIcon("play.fill", size = 12.dp, tint = c.onAccent)
                PearText("Play", style = PearTheme.type.subheadline.weight(FontWeight.Bold), color = c.onAccent)
            }
            AccentBadge("UP NEXT")
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.62f).clip(CircleShape).background(c.gradient))
        }
    }
}

/** A line of the summary: an icon that is ticked or dashed, the title, and the value. */
@Composable
internal fun SummaryRow(title: String, value: String, on: Boolean) {
    val c = PearTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PearIcon(
            if (on) "checkmark.circle.fill" else "circle.dashed",
            size = 18.dp,
            tint = if (on) c.green else c.textSecondary,
        )
        PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        Spacer(Modifier.weight(1f))
        PearText(value, style = PearTheme.type.subheadline, color = c.textSecondary)
    }
}

/**
 * Two groups side by side on the wide frame, one above the other on the phone. Each group stacks its own children.
 */
@Composable
internal fun ColumnScope.TwoUp(
    wide: Boolean,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = right)
        }
    } else {
        left()
        right()
    }
}

/** The accent glow at the top of a page, as iOS paints it. */
internal fun Modifier.accentWash(accent: Color): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.32f), Color.Transparent),
            center = Offset(size.width / 2f, 0f),
            radius = 520.dp.toPx(),
        ),
    )
}

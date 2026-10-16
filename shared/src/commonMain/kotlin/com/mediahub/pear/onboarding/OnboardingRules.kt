package com.mediahub.pear.onboarding

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.Profile
import com.mediahub.pear.shell.LayoutMode
import com.mediahub.pear.shell.LayoutOverride
import com.mediahub.pear.shell.PlatformCaps
import com.mediahub.pear.shell.layoutModeFor

/** The steps of the setup, in order (iOS `OnboardingView`'s `Step`). Player is Android only; see [OnboardingRules.steps]. */
enum class OnboardingStep {
    Welcome, Profile, Look, Player, Content, Home, Keys, Done;

    /** The next step this platform shows, or null after the last. */
    fun next(): OnboardingStep? {
        val i = OnboardingRules.steps.indexOf(this)
        return if (i < 0) null else OnboardingRules.steps.getOrNull(i + 1)
    }

    /** The step before this one that this platform shows, or null before the first. */
    fun previous(): OnboardingStep? {
        val i = OnboardingRules.steps.indexOf(this)
        return if (i < 0) null else OnboardingRules.steps.getOrNull(i - 1)
    }
}

/** The two frames: one column for a phone, or the wide two-pane frame for a window. */
enum class OnboardingLayout { Phone, Wide }

/**
 * The rules of the setup, as plain functions so they can be tested without the UI. They follow `OnboardingView.swift`.
 */
object OnboardingRules {
    /** The longest name a profile can take, as on iOS. */
    const val MAX_NAME = 20

    /** The steps this platform shows. The player-glass step is Android only, so desktop never sees it. */
    val steps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter { it != OnboardingStep.Player || PlatformCaps.hasPlayerGlassChoice }

    /** From this window width the wide frame shows its step rail beside the form. */
    val railMinWidth: Dp = 900.dp

    fun clampName(typed: String): String = typed.take(MAX_NAME)

    /** Simkl's name and picture are in use: Simkl is connected, an account is loaded, and the switch is on. */
    fun simklActive(connected: Boolean, hasAccount: Boolean, useSimkl: Boolean): Boolean =
        connected && hasAccount && useSimkl

    /** The profile step needs Simkl in use or a name typed. No other step blocks. */
    fun canContinue(step: OnboardingStep, simklActive: Boolean, name: String): Boolean =
        step != OnboardingStep.Profile || simklActive || name.isNotBlank()

    /** Keys says "Skip for now" while nothing is typed and there is no built-in TMDB key to use. */
    fun primaryLabel(step: OnboardingStep, anyKeyTyped: Boolean, hasProxy: Boolean): String = when (step) {
        OnboardingStep.Welcome -> "Get Started"
        OnboardingStep.Keys -> if (!anyKeyTyped && !hasProxy) "Skip for now" else "Continue"
        OnboardingStep.Done -> "Start Watching"
        else -> "Continue"
    }

    fun showBack(step: OnboardingStep): Boolean = step != OnboardingStep.Welcome && step != OnboardingStep.Done

    fun showSkip(step: OnboardingStep): Boolean = step != OnboardingStep.Done

    /**
     * The frame for a window: wide from the shell's breakpoint, phone below it. The shell's layout override wins.
     * With [allowWide] false (a phone) it is always the phone frame.
     */
    fun layoutFor(width: Dp, override: LayoutOverride = LayoutOverride.Auto, allowWide: Boolean = true): OnboardingLayout =
        if (layoutModeFor(width, override, allowWide) == LayoutMode.Expanded) OnboardingLayout.Wide else OnboardingLayout.Phone

    /** The step rail shows beside the form only when the window is wide enough for both panes. */
    fun showsRail(width: Dp): Boolean = width >= railMinWidth

    /** The name the avatar preview uses: the typed one, else the Simkl account's, else "?". */
    fun previewName(typed: String, simklName: String?): String = typed.trim().ifEmpty { simklName ?: "?" }

    /** The name the setup greets the user with: the Simkl name while Simkl is in use, else the typed name, else "friend". */
    fun shownName(typed: String, simklActive: Boolean, simklName: String?): String {
        if (simklActive && simklName != null) return simklName
        return previewName(typed, simklName).let { if (it == "?") "friend" else it }
    }

    /**
     * What the first profile becomes when setup finishes (iOS `commit()`): the typed name, else the Simkl name while Simkl
     * is in use, else the name it already had. The accent becomes its colour, unless the system accent is in use.
     */
    fun commitProfile(
        active: Profile,
        typedName: String,
        simklActive: Boolean,
        simklName: String?,
        accentHex: String,
        systemAccent: Boolean,
    ): Profile {
        val typed = typedName.trim()
        val name = when {
            typed.isNotEmpty() -> typed
            simklActive && !simklName.isNullOrBlank() -> simklName
            else -> active.name
        }
        return active.copy(
            name = name,
            simklIdentity = if (simklActive) true else null,
            colorHex = if (systemAccent) active.colorHex else accentHex,
        )
    }

    /** A catalogue's type as the Home step names it: Movies, Series, otherwise the type itself. */
    fun typeLabel(type: String): String = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** The label of a category, as iOS `ContentCategory.title`. */
    fun categoryTitle(c: ContentCategory): String = when (c) {
        ContentCategory.Anime -> "Anime"
        ContentCategory.Cartoons -> "Cartoons & animation"
        ContentCategory.Kids -> "Kids & family"
        ContentCategory.Documentary -> "Documentaries"
        ContentCategory.Reality -> "Reality TV"
        ContentCategory.TalkNews -> "Talk shows & news"
        ContentCategory.Soap -> "Soap operas"
        ContentCategory.Horror -> "Horror"
        ContentCategory.Romance -> "Romance"
        ContentCategory.Asian -> "Asian film & TV"
        ContentCategory.Indian -> "Indian & South Asian"
        ContentCategory.Foreign -> "Other foreign-language"
        ContentCategory.Music -> "Music & musicals"
        ContentCategory.TvMovies -> "TV movies"
    }

    /**
     * A category's icon. iOS uses SF Symbols that the Kotlin icon map doesn't have, so each gets the nearest one it does have.
     */
    fun categorySymbol(c: ContentCategory): String = when (c) {
        ContentCategory.Anime -> "sparkles"
        ContentCategory.Cartoons -> "pawprint.fill"
        ContentCategory.Kids -> "hare.fill"
        ContentCategory.Documentary -> "globe"
        ContentCategory.Reality -> "person.fill"
        ContentCategory.TalkNews -> "captions.bubble"
        ContentCategory.Soap -> "theatermasks.fill"
        ContentCategory.Horror -> "eye.fill"
        ContentCategory.Romance -> "heart.fill"
        ContentCategory.Asian -> "globe"
        ContentCategory.Indian -> "sun.max.fill"
        ContentCategory.Foreign -> "safari"
        ContentCategory.Music -> "waveform"
        ContentCategory.TvMovies -> "tv"
    }
}

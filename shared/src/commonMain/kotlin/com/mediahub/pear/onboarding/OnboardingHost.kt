package com.mediahub.pear.onboarding

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.Profile
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.theme.Accents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What the setup has collected so far, and what it writes as it goes. Accent, content rules, add-ons and keys are written
 * as they change, as on iOS. The profile is written when the setup ends ([complete]).
 */
internal class OnboardingDraft(private val env: AppEnv) {
    private val prefs = env.data.prefs

    var step by mutableStateOf(OnboardingStep.Welcome)
    var name by mutableStateOf("")
    var useSimkl by mutableStateOf(true)
    var urlText by mutableStateOf("")
    var addError by mutableStateOf<String?>(null)
    var adding by mutableStateOf(false)
    var ownTmdb by mutableStateOf(false)

    /** A text field has focus, so Enter is the field's, not the setup's. */
    var editing by mutableStateOf(false)
    var customHex by mutableStateOf("")

    var tmdbKey by mutableStateOf(prefs.string(PrefKeys.TMDB_KEY, ""))
    var tvdbKey by mutableStateOf(prefs.string(PrefKeys.TVDB_KEY, ""))
    var tvdbPin by mutableStateOf(prefs.string(PrefKeys.TVDB_PIN, ""))
    var mdbKey by mutableStateOf(prefs.string(PrefKeys.MDBLIST_KEY, ""))

    val simklConnected: Boolean get() = env.data.simkl.isConnected
    val simklName: String? get() = env.data.simkl.account?.name
    val simklActive: Boolean
        get() = OnboardingRules.simklActive(simklConnected, env.data.simkl.account != null, useSimkl)
    val canContinue: Boolean get() = OnboardingRules.canContinue(step, simklActive, name)
    val hasProxy: Boolean get() = env.data.meta.tmdb.hasProxy
    val anyKeyTyped: Boolean get() = tmdbKey.isNotBlank() || tvdbKey.isNotBlank() || mdbKey.isNotBlank()

    /** The TMDB switch: on when the user turned it on or a key is already saved. */
    val ownTmdbOn: Boolean get() = ownTmdb || tmdbKey.isNotEmpty()

    /** The name the setup greets the user with. */
    val shownName: String get() = OnboardingRules.shownName(name, simklActive, simklName)

    /** The profile the avatar previews while the setup runs. */
    val previewProfile: Profile
        get() = Profile(
            id = "preview",
            name = OnboardingRules.previewName(name, simklName),
            colorHex = if (env.theme.isSystemAccent) Accents.DEFAULT_HEX else env.theme.accentHex,
            simklIdentity = if (simklActive) true else null,
        )

    fun setName(typed: String) {
        name = OnboardingRules.clampName(typed)
    }

    fun setCustomHex(typed: String) {
        val v = typed.trim()
        customHex = v
        val digits = if (v.startsWith("#")) v.drop(1) else v
        if (digits.length == 6 && Accents.parseHex(v) != null) env.theme.setAccent(v)
    }

    fun setTmdbKey(typed: String) {
        tmdbKey = typed
        prefs.setString(PrefKeys.TMDB_KEY, typed.trim())
    }

    fun setTvdbKey(typed: String) {
        tvdbKey = typed
        prefs.setString(PrefKeys.TVDB_KEY, typed.trim())
    }

    fun setTvdbPin(typed: String) {
        tvdbPin = typed
        prefs.setString(PrefKeys.TVDB_PIN, typed.trim())
    }

    fun setMdbKey(typed: String) {
        mdbKey = typed
        prefs.setString(PrefKeys.MDBLIST_KEY, typed.trim())
    }

    /** Switching the own key off removes it, so the built-in one is the one that gets used. */
    fun setOwnTmdb(on: Boolean) {
        ownTmdb = on
        if (!on) {
            tmdbKey = ""
            prefs.remove(PrefKeys.TMDB_KEY)
        }
    }

    /** Writes the choices to the first profile, marks the setup done and hides it (iOS `complete()`). */
    fun complete() {
        val profile = OnboardingRules.commitProfile(
            active = env.data.profiles.active,
            typedName = name,
            simklActive = simklActive,
            simklName = simklName,
            accentHex = env.theme.accentHex,
            systemAccent = env.theme.isSystemAccent,
        )
        env.data.profiles.update(profile)
        OnboardingState.finish(prefs)
        env.shell.onboardingVisible = false
    }
}

/**
 * The first-launch setup, drawn over the whole app while it is pending. Owns the draft, picks the frame for the window, and
 * takes the keys: Enter goes forward (unless a text field has focus), Alt+Left goes back, and Esc does nothing, so the setup
 * can't be skipped by accident. Shell shortcuts don't reach the app behind it.
 */
@Composable
fun OnboardingHost(modifier: Modifier = Modifier) {
    val env = LocalAppEnv.current
    val simkl = env.data.simkl
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val draft = remember(env) { OnboardingDraft(env) }

    // Signing in turns Simkl's name on, as iOS does.
    LaunchedEffect(simkl.account) {
        if (simkl.account != null) draft.useSimkl = true
    }
    // Right after a first sign-in, pull the library so Home and Library are filled when the setup ends.
    val startedConnected = remember { simkl.isConnected }
    LaunchedEffect(simkl.isConnected) {
        if (simkl.isConnected && !startedConnected) simkl.sync(force = true)
    }

    // The setup takes the keyboard while it is up, so the shell behind it doesn't see the shortcuts.
    LaunchedEffect(draft.step) {
        runCatching { focus.requestFocus() }
    }

    fun go(to: OnboardingStep) {
        focusManager.clearFocus()
        draft.editing = false
        draft.step = to
    }

    fun primary() {
        if (draft.step == OnboardingStep.Done) {
            draft.complete()
            return
        }
        if (!draft.canContinue) return
        draft.step.next()?.let { go(it) }
    }

    fun back() {
        if (OnboardingRules.showBack(draft.step)) draft.step.previous()?.let { go(it) }
    }

    fun skip() {
        draft.complete()
    }

    fun jump(to: OnboardingStep) {
        if (to < draft.step) go(to)
    }

    fun addAddon() {
        val url = draft.urlText.trim()
        if (url.isEmpty() || draft.adding) return
        scope.launch {
            draft.adding = true
            try {
                env.data.addons.add(url)
                draft.urlText = ""
                draft.addError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draft.addError = "Couldn't load that manifest. Check the link and try again."
            } finally {
                draft.adding = false
            }
        }
    }

    val actions = OnboardingActions(
        onPrimary = { primary() },
        onBack = { back() },
        onSkip = { skip() },
        onJump = { jump(it) },
        onAddAddon = { addAddon() },
    )

    Box(
        modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> handleKey(event, draft, onPrimary = { primary() }, onBack = { back() }) },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = OnboardingRules.layoutFor(maxWidth, env.shell.layoutOverride)
            when (layout) {
                OnboardingLayout.Phone -> OnboardingPhone(draft, actions)
                OnboardingLayout.Wide -> OnboardingWide(draft, actions, showRail = OnboardingRules.showsRail(maxWidth))
            }
        }
    }
}

/**
 * The keys the setup takes. Esc is swallowed so it can't skip the setup by accident. Enter goes forward unless a text field
 * has focus, because Enter there belongs to the field (Done clears its focus).
 */
private fun handleKey(event: KeyEvent, draft: OnboardingDraft, onPrimary: () -> Unit, onBack: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.Escape -> true
        event.isAltPressed && event.key == Key.DirectionLeft -> {
            onBack()
            true
        }
        event.key == Key.Enter && !draft.editing -> {
            onPrimary()
            true
        }
        else -> false
    }
}

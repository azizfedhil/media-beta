package com.mediahub.pear.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediahub.pear.addons.AddonStore
import com.mediahub.pear.addons.rowKey
import com.mediahub.pear.brand.PearWordmark
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.ContentPrefs
import com.mediahub.pear.data.isRowAdded
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.model.Addon
import com.mediahub.pear.profile.ProfileAvatar
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.simkl.SimklConnectControls
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/**
 * The body of one step, shared by both frames. [wide] sets two groups side by side, and [headerInForm] puts the step's
 * title in the form (the wide frame's rail shows it instead when the rail is there).
 */
@Composable
internal fun ColumnScope.OnboardingStepBody(
    step: OnboardingStep,
    wide: Boolean,
    headerInForm: Boolean,
    draft: OnboardingDraft,
    actions: OnboardingActions,
) {
    val hero = step == OnboardingStep.Welcome || step == OnboardingStep.Done
    if (headerInForm && !hero) StepHeader(stepTitle(step), stepSubtitle(step, draft.hasProxy))
    when (step) {
        OnboardingStep.Welcome -> WelcomeBody(headerInForm)
        OnboardingStep.Profile -> ProfileBody(draft)
        OnboardingStep.Look -> LookBody(wide, draft)
        OnboardingStep.Player -> PlayerGlassBody()
        OnboardingStep.Content -> ContentBody(wide)
        OnboardingStep.Home -> HomeBody(wide, draft, actions)
        OnboardingStep.Keys -> KeysBody(wide, draft)
        OnboardingStep.Done -> DoneBody(wide, draft)
    }
}

@Composable
private fun ColumnScope.WelcomeBody(headerInForm: Boolean) {
    if (headerInForm) {
        PearWordmark(height = 64.dp)
        PearText(
            "Everything you watch, in one place.\nA minute of setup makes it yours.",
            style = PearTheme.type.title3,
            color = PearTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
    Column(Modifier.widthIn(max = 320.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WelcomeRow("person.crop.circle", "Sign in or pick a name")
        WelcomeRow("star.fill", "Choose your colour")
        WelcomeRow("square.grid.2x2.fill", "Decide what shows on Home")
        WelcomeRow("lock.fill", "Add your API keys")
    }
}

@Composable
private fun WelcomeRow(symbol: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PearIcon(symbol, size = 22.dp, tint = PearTheme.colors.accent)
        PearText(text, style = PearTheme.type.body.weight(FontWeight.Medium))
    }
}

@Composable
private fun ColumnScope.ProfileBody(draft: OnboardingDraft) {
    ProfileAvatar(profile = draft.previewProfile, size = 88.dp)
    OnboardingCard {
        CardLabel("Sign in with Simkl", symbol = "arrow.triangle.2.circlepath")
        if (draft.simklConnected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PearIcon("checkmark.circle.fill", size = 16.dp, tint = PearTheme.colors.green)
                PearText(
                    "Connected",
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    color = PearTheme.colors.green,
                )
            }
            draft.simklName?.let { account ->
                ToggleRow(
                    title = "Use my Simkl name and picture",
                    checked = draft.useSimkl,
                    onChange = { draft.useSimkl = it },
                    detail = account,
                )
            }
        } else {
            Footnote(
                "Syncs your library and watch history, and uses your Simkl name and picture. " +
                    "You enter a code on simkl.com/pin; no account setup needed.",
            )
            SimklConnectControls(compact = true)
        }
    }
    OnboardingCard {
        CardLabel("Pick a name", symbol = "person.fill")
        OnboardingField(
            value = draft.name,
            onValueChange = { draft.updateName(it) },
            placeholder = "Name",
            onFocusChange = { draft.editing = it },
        )
        Footnote(
            if (draft.simklActive) {
                "Optional while your Simkl name is in use. It is kept as a fallback."
            } else {
                "This is how your profile is shown. You can add more profiles later."
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LookBody(wide: Boolean, draft: OnboardingDraft) {
    val theme = LocalAppEnv.current.theme
    TwoUp(
        wide = wide,
        left = {
            OnboardingCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SystemSwatch(selected = theme.isSystemAccent, onClick = { theme.setAccent(Accents.SYSTEM) })
                    Accents.presets.forEach { preset ->
                        ColourSwatch(
                            color = Accents.parseHex(preset.hex) ?: Color.Gray,
                            selected = theme.accentHex.equals(preset.hex, ignoreCase = true),
                            label = preset.name,
                            onClick = { theme.setAccent(preset.hex) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PearText("Custom colour", style = PearTheme.type.subheadline.weight(FontWeight.Medium))
                    OnboardingField(
                        value = draft.customHex,
                        onValueChange = { draft.updateCustomHex(it) },
                        placeholder = "#RRGGBB",
                        modifier = Modifier.weight(1f),
                        onFocusChange = { draft.editing = it },
                    )
                }
            }
        },
        right = { PreviewCard() },
    )
}

/**
 * The player step (Android only): how the player's controls look. Frosted is the default. The choice is written at once
 * and read back from the setting, so it survives Back and Next. The warning for Liquid Glass always shows.
 *
 * It is one column on both frames: the preview card beside the other steps shows the accent, not the glass, so it is not
 * used here.
 */
@Composable
private fun ColumnScope.PlayerGlassBody() {
    val prefs = LocalAppEnv.current.data.prefs
    val liquid = rememberPref(prefs, PrefKeys.ANDROID_PLAYER_LIQUID_GLASS) { it.bool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false) }
    OnboardingCard {
        GlassChoice(
            title = "Frosted glass",
            line = "Soft, light and easy on the battery.",
            tag = "Recommended",
            selected = !liquid,
            onSelect = { prefs.setBool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false) },
        )
        GlassChoice(
            title = "Liquid Glass",
            line = "Live blur and refraction, like the rest of the app.",
            selected = liquid,
            onSelect = { prefs.setBool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, true) },
            warning = "Can use more battery, especially on long films and older phones.",
        )
    }
}

/**
 * One of the two player looks: a title, a short line, a selected state, and an optional tag or warning. The whole card is
 * the touch target (48 dp or more).
 */
@Composable
private fun GlassChoice(
    title: String,
    line: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String? = null,
    warning: String? = null,
) {
    val c = PearTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.md))
            .background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PearIcon(
                if (selected) "checkmark.circle.fill" else "circle",
                size = 20.dp,
                tint = if (selected) c.accent else c.textTertiary,
            )
            PearText(title, Modifier.weight(1f), style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            if (tag != null) {
                PearText(tag, style = PearTheme.type.caption.weight(FontWeight.Bold), color = c.accent)
            }
        }
        PearText(line, Modifier.padding(start = 30.dp), style = PearTheme.type.footnote, color = c.textSecondary)
        if (warning != null) {
            Row(Modifier.padding(start = 30.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PearIcon("exclamationmark.triangle.fill", size = 14.dp, tint = c.orange)
                PearText(warning, Modifier.weight(1f), style = PearTheme.type.footnote, color = c.orange)
            }
        }
    }
}

@Composable
private fun ColumnScope.ContentBody(wide: Boolean) {
    val content = LocalAppEnv.current.data.content
    TwoUp(
        wide = wide,
        left = {
            OnboardingCard {
                PearText("Where it's from", style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary)
                ContentCategory.regions.forEach { cat -> CategoryToggle(cat, content) }
            }
        },
        right = {
            OnboardingCard {
                PearText("Kinds of titles", style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary)
                ContentCategory.entries.filter { !it.isRegion }.forEach { cat -> CategoryToggle(cat, content) }
            }
        },
    )
    Footnote("Chosen per profile. Find it again in Settings → Content & catalogues.")
}

@Composable
private fun CategoryToggle(cat: ContentCategory, content: ContentPrefs) {
    ToggleRow(
        title = OnboardingRules.categoryTitle(cat),
        checked = !content.rules.hiddenCategories.contains(cat),
        onChange = { on -> content.setHidden(cat, !on) },
        symbol = OnboardingRules.categorySymbol(cat),
    )
}

@Composable
private fun ColumnScope.HomeBody(wide: Boolean, draft: OnboardingDraft, actions: OnboardingActions) {
    val env = LocalAppEnv.current
    val content = env.data.content
    val addons = env.data.addons
    val pickers = addons.addons.filter { addons.usesCatalogPicker(it) }
    TwoUp(
        wide = wide,
        left = {
            OnboardingCard {
                PearText("Home rows", style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary)
                BuiltinRowToggle("Trending Movies", BuiltinRows.TRENDING_MOVIES, "flame.fill", content)
                BuiltinRowToggle("Trending Shows", BuiltinRows.TRENDING_SHOWS, "flame.fill", content)
                BuiltinRowToggle("Because you watched", BuiltinRows.BECAUSE, "sparkles", content)
                BuiltinRowToggle("Themed collections", BuiltinRows.THEMES, "square.stack.fill", content)
            }
            AddonsCard(draft, actions, addons)
        },
        right = {
            pickers.forEach { addon -> CatalogueCard(addon, content) }
        },
    )
}

@Composable
private fun BuiltinRowToggle(title: String, key: String, symbol: String, content: ContentPrefs) {
    ToggleRow(
        title = title,
        checked = !content.rules.isRowHidden(key),
        onChange = { on -> content.setRowHidden(key, !on) },
        symbol = symbol,
    )
}

@Composable
private fun AddonsCard(draft: OnboardingDraft, actions: OnboardingActions, addons: AddonStore) {
    val installed = addons.addons
    val error = draft.addError
    OnboardingCard {
        CardLabel("Add-ons", symbol = "puzzlepiece.extension.fill")
        when {
            installed.isNotEmpty() -> installed.forEach { addon ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearIcon("checkmark.circle.fill", size = 16.dp, tint = PearTheme.colors.green)
                    PearText(addon.manifest.name, style = PearTheme.type.subheadline)
                }
            }
            addons.isRestoring -> Footnote("Loading…")
            else -> Footnote("No add-ons yet.")
        }
        OnboardingField(
            value = draft.urlText,
            onValueChange = { draft.urlText = it },
            placeholder = "Add-on link (https:// or stremio://)",
            onFocusChange = { draft.editing = it },
        )
        GlassButton(
            onClick = actions.onAddAddon,
            label = if (draft.adding) "Adding…" else "Add add-on",
            enabled = draft.urlText.isNotBlank() && !draft.adding,
            tint = PearTheme.colors.accent,
            modifier = Modifier.width(170.dp),
        )
        if (error != null) {
            Footnote(error, color = PearTheme.colors.red)
        } else {
            Footnote(
                "Paste an add-on's manifest link, for example your AIOMetadata. " +
                    "Catalogues from AIOMetadata only show on Home once you pick them below.",
            )
        }
    }
}

/** An add-on's catalogues that can be picked, with All and None. Shown for the add-ons that list them (see `usesCatalogPicker`). */
@Composable
private fun CatalogueCard(addon: Addon, content: ContentPrefs) {
    val all = addon.homeCatalogs
    val keys = all.map { addon.rowKey(it) }
    OnboardingCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PearText("${addon.manifest.name} catalogues", style = PearTheme.type.headline, modifier = Modifier.weight(1f))
            TextAction("All", onClick = { content.setRowsAdded(keys, true) })
            Spacer(Modifier.width(14.dp))
            TextAction("None", onClick = { content.setRowsAdded(keys, false) })
        }
        all.take(60).forEach { cat ->
            val key = addon.rowKey(cat)
            ToggleRow(
                title = "${cat.name ?: cat.id} · ${OnboardingRules.typeLabel(cat.type)}",
                checked = content.rules.isRowAdded(key),
                onChange = { on -> content.setRowAdded(key, on) },
            )
        }
        if (all.size > 60) Footnote("${all.size - 60} more in Settings → Content & catalogues.")
    }
}

@Composable
private fun ColumnScope.KeysBody(wide: Boolean, draft: OnboardingDraft) {
    if (draft.hasProxy) {
        BuiltInTmdbCard(draft)
    } else {
        KeyCard(
            title = "TMDB",
            badge = "Recommended",
            symbol = "film.stack",
            text = "Trending, Explore, recommendations, episode thumbnails, title logos and skip-intro matching. " +
                "Without it, most of the app stays empty.",
            placeholder = "TMDB API key",
            value = draft.tmdbKey,
            onValueChange = { draft.updateTmdbKey(it) },
            link = "https://www.themoviedb.org/settings/api",
            linkLabel = "Get a free key",
            onFocusChange = { draft.editing = it },
        )
    }
    TwoUp(
        wide = wide,
        left = {
            KeyCard(
                title = "MDBList",
                badge = null,
                symbol = "list.bullet",
                text = "IMDb, Rotten Tomatoes, Metacritic and Letterboxd ratings, plus your lists on Home.",
                placeholder = "MDBList API key",
                value = draft.mdbKey,
                onValueChange = { draft.updateMdbKey(it) },
                link = "https://mdblist.com/preferences",
                linkLabel = "Get a key",
                onFocusChange = { draft.editing = it },
            )
        },
        right = {
            KeyCard(
                title = "TheTVDB",
                badge = null,
                symbol = "tv",
                text = "Fills in missing episode thumbnails and title logos.",
                placeholder = "TVDB API key",
                value = draft.tvdbKey,
                onValueChange = { draft.updateTvdbKey(it) },
                link = "https://thetvdb.com/api-information",
                linkLabel = "Get a key",
                onFocusChange = { draft.editing = it },
            ) {
                OnboardingField(
                    value = draft.tvdbPin,
                    onValueChange = { draft.updateTvdbPin(it) },
                    placeholder = "PIN (subscriber keys only)",
                    secret = true,
                    onFocusChange = { draft.editing = it },
                )
            }
        },
    )
}

/** TMDB is built in: the card says so, and a switch turns on the own key for when the built-in one fails. */
@Composable
private fun ColumnScope.BuiltInTmdbCard(draft: OnboardingDraft) {
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PearIcon("film.stack", size = 18.dp, tint = PearTheme.colors.accent)
            PearText("TMDB", style = PearTheme.type.headline)
            AccentBadge("Built in")
            Spacer(Modifier.weight(1f))
            PearIcon("checkmark.circle.fill", size = 18.dp, tint = PearTheme.colors.green)
        }
        Footnote(
            "You don't need to provide a key. Pear. already includes one, so trending, Explore, recommendations, " +
                "episode thumbnails, title logos and skip-intro matching work out of the box. If something doesn't load, " +
                "turn on the switch below and add your own free key.",
        )
        ToggleRow(
            title = "Use my own TMDB key",
            checked = draft.ownTmdbOn,
            onChange = { draft.updateOwnTmdb(it) },
        )
        if (draft.ownTmdbOn) {
            OnboardingField(
                value = draft.tmdbKey,
                onValueChange = { draft.updateTmdbKey(it) },
                placeholder = "TMDB API key",
                secret = true,
                onFocusChange = { draft.editing = it },
            )
            LinkText("Get a free key", "https://www.themoviedb.org/settings/api")
        }
    }
}

/**
 * A key's card: its title and an optional badge, what it is for, the masked field, an optional extra field, and a link to
 * where the key comes from. The tick shows once a key is typed.
 */
@Composable
private fun KeyCard(
    title: String,
    badge: String?,
    symbol: String,
    text: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    link: String,
    linkLabel: String,
    onFocusChange: (Boolean) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PearIcon(symbol, size = 18.dp, tint = PearTheme.colors.accent)
            PearText(title, style = PearTheme.type.headline)
            if (badge != null) AccentBadge(badge)
            Spacer(Modifier.weight(1f))
            if (value.isNotBlank()) PearIcon("checkmark.circle.fill", size = 18.dp, tint = PearTheme.colors.green)
        }
        Footnote(text)
        OnboardingField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            secret = true,
            onFocusChange = onFocusChange,
        )
        extra()
        LinkText(linkLabel, link)
    }
}

@Composable
private fun ColumnScope.DoneBody(wide: Boolean, draft: OnboardingDraft) {
    val c = PearTheme.colors
    val tmdbAdded = draft.tmdbKey.isNotEmpty()
    val mdbAdded = draft.mdbKey.isNotEmpty()
    val tvdbAdded = draft.tvdbKey.isNotEmpty()
    TwoUp(
        wide = wide,
        left = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileAvatar(profile = draft.previewProfile, size = 96.dp)
                PearText(
                    "You're all set, ${draft.shownName}",
                    style = PearTheme.type.title2.weight(FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
            }
        },
        right = {
            OnboardingCard {
                SummaryRow(
                    title = "Simkl",
                    value = if (draft.simklConnected) "Connected" else "Not connected",
                    on = draft.simklConnected,
                )
                SummaryRow(
                    title = "TMDB key",
                    value = when {
                        tmdbAdded -> "Added"
                        draft.hasProxy -> "Built in"
                        else -> "Not added"
                    },
                    on = tmdbAdded || draft.hasProxy,
                )
                SummaryRow(title = "MDBList key", value = if (mdbAdded) "Added" else "Not added", on = mdbAdded)
                SummaryRow(title = "TheTVDB key", value = if (tvdbAdded) "Added" else "Not added", on = tvdbAdded)
            }
        },
    )
    Footnote("Everything here can be changed in Settings.", color = c.textSecondary, textAlign = TextAlign.Center)
}

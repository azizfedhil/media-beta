package com.mediahub.pear.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore

enum class Appearance(val label: String) {
    /** The Swift app's behaviour (`Theme.forceDark = true`): colourful glows and hero art read best on black. */
    Dark("Dark"),
    Light("Light"),
    /** Follow the operating system. */
    System("System"),
}

/**
 * The user's look: accent colour and light/dark choice. `accentHex` is saved under the iOS `ui.accent` key
 * (`"#RRGGBB"` or `"system"`) when [prefs] is given; appearance under the desktop-only `desktop.appearance`.
 */
class ThemeStore(
    initialAccentHex: String = Accents.DEFAULT_HEX,
    initialAppearance: Appearance = Appearance.Dark,
    private val prefs: PrefStore? = null,
) {
    var accentHex: String by mutableStateOf(Accents.normalize(prefs?.string(PrefKeys.UI_ACCENT) ?: initialAccentHex))
        private set

    private var appearanceState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_APPEARANCE)?.let { n -> Appearance.entries.firstOrNull { it.name == n } } ?: initialAppearance,
    )
    var appearance: Appearance
        get() = appearanceState
        set(value) {
            appearanceState = value
            prefs?.setString(PrefKeys.DESKTOP_APPEARANCE, value.name)
        }

    val isSystemAccent: Boolean get() = accentHex == Accents.SYSTEM

    fun setAccent(hex: String) {
        accentHex = Accents.normalize(hex)
        prefs?.setString(PrefKeys.UI_ACCENT, accentHex)
    }

    /** Re-reads from settings (after a backup import). */
    fun reload() {
        val p = prefs ?: return
        accentHex = Accents.normalize(p.string(PrefKeys.UI_ACCENT))
    }
}

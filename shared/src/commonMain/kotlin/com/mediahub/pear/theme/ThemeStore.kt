package com.mediahub.pear.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Appearance(val label: String) {
    /** The Swift app's behaviour (`Theme.forceDark = true`): colourful glows and hero art read best on black. */
    Dark("Dark"),
    Light("Light"),
    /** Follow the operating system. */
    System("System"),
}

/**
 * The user's look: accent colour and light/dark choice. `accentHex` holds the same value as the iOS `ui.accent`
 * key (`"#RRGGBB"` or `"system"`), so Batch 2.1 can persist it under that key unchanged.
 */
class ThemeStore(
    initialAccentHex: String = Accents.DEFAULT_HEX,
    initialAppearance: Appearance = Appearance.Dark,
) {
    var accentHex: String by mutableStateOf(Accents.normalize(initialAccentHex))
        private set
    var appearance: Appearance by mutableStateOf(initialAppearance)

    val isSystemAccent: Boolean get() = accentHex == Accents.SYSTEM

    fun setAccent(hex: String) {
        accentHex = Accents.normalize(hex)
    }
}

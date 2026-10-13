package com.mediahub.pear.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Every colour the UI uses. Dark values mirror iOS dark mode (the Swift app forces dark: `Theme.forceDark`),
 * light values mirror iOS light mode, so a light appearance is available but not the default.
 * Surfaces that sit on photos use the translucent fills; opaque ones are [background], [surface] and [sidebar].
 */
class PearColors(
    val isDark: Boolean,
    // accent (the user's choice)
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    // surfaces
    val background: Color,
    /** Base colour of glass and flat panels (menus, sheets, bars). */
    val surface: Color,
    val sidebar: Color,
    // translucent fills and lines
    /** Card fill, hover on rows (Swift: white at 7 to 8%). */
    val fillSubtle: Color,
    /** Pressed and selected fills, chips (Swift: white at 12%). */
    val fill: Color,
    val fillStrong: Color,
    /** Border of glass surfaces. */
    val outline: Color,
    /** Thin divider lines (sidebar edge, menu dividers). */
    val hairline: Color,
    // text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    // status
    val red: Color,
    val green: Color,
    val orange: Color,
    val yellow: Color,
    val blue: Color,
) {
    /** The accent gradient (iOS `ThemeStore.gradient`): the accent into its neighbouring hue, left to right. */
    val gradient: Brush get() = Brush.horizontalGradient(listOf(accent, accent2))

    /**
     * The accent gradient, faint. Sits behind menus, cards, buttons and sheets so they stand off a black background,
     * the way the iOS cards and menus take the accent. Glass only tints the blur; this is what gives it colour.
     */
    val sheen: Brush get() = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.34f), accent2.copy(alpha = 0.12f)))

    /**
     * The tint of the sidebar and of every settings card: the same dark wash, so the cards read as the sidebar does.
     * (Not the accent sheen: a card is a panel, not a control.)
     */
    val panel: Color get() = Color(0xFF0D0D11).copy(alpha = 0.55f)

    /**
     * Menus and cards (the context menu, the player's panels): the accent, strong enough to read as a coloured box over the
     * dark glass. Stronger than [sheen], which is for the glass boxes of the pages.
     */
    val menu: Brush get() = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.42f), accent2.copy(alpha = 0.16f)))

    /** Untinted buttons: a clear accent fill, so a button stands off its backdrop. */
    val button: Brush get() = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), accent2.copy(alpha = 0.3f)))

    /** Name and colour of every token, for the theme preview screen. */
    fun swatches(): List<Pair<String, Color>> = listOf(
        "accent" to accent, "accent2" to accent2, "onAccent" to onAccent,
        "background" to background, "surface" to surface, "sidebar" to sidebar,
        "fillSubtle" to fillSubtle, "fill" to fill, "fillStrong" to fillStrong,
        "outline" to outline, "hairline" to hairline,
        "textPrimary" to textPrimary, "textSecondary" to textSecondary,
        "textTertiary" to textTertiary, "textDisabled" to textDisabled,
        "red" to red, "green" to green, "orange" to orange, "yellow" to yellow, "blue" to blue,
    )
}

/** Build the palette for a stored accent (`"#RRGGBB"` or `"system"`, see [Accents]) and an appearance. */
fun pearColors(accentHex: String, dark: Boolean): PearColors {
    val a = Accents.resolve(accentHex, dark)
    return if (dark) PearColors(
        isDark = true,
        accent = a.accent, accent2 = a.accent2, onAccent = a.onAccent,
        background = Color(0xFF000000),
        surface = Color(0xFF1C1C1E),
        sidebar = Color(0xFF121212), // Swift: Color(white: 0.07)
        fillSubtle = Color(0x14FFFFFF), // white 8%
        fill = Color(0x1FFFFFFF), // white 12%
        fillStrong = Color(0x2EFFFFFF), // white 18%
        outline = Color(0x24FFFFFF), // white 14%
        hairline = Color(0x12FFFFFF), // white 7%
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0x99EBEBF5), // iOS secondaryLabel
        textTertiary = Color(0x4DEBEBF5), // iOS tertiaryLabel
        textDisabled = Color(0x2EEBEBF5),
        red = Color(0xFFFF453A), green = Color(0xFF30D158), orange = Color(0xFFFF9F0A),
        yellow = Color(0xFFFFD60A), blue = Color(0xFF0A84FF),
    ) else PearColors(
        isDark = false,
        accent = a.accent, accent2 = a.accent2, onAccent = a.onAccent,
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF2F2F7),
        sidebar = Color(0xFFF2F2F7),
        fillSubtle = Color(0x0A000000), // black 4%
        fill = Color(0x14000000), // black 8%
        fillStrong = Color(0x1F000000), // black 12%
        outline = Color(0x1A000000), // black 10%
        hairline = Color(0x14000000),
        textPrimary = Color(0xFF000000),
        textSecondary = Color(0x993C3C43),
        textTertiary = Color(0x4D3C3C43),
        textDisabled = Color(0x2E3C3C43),
        red = Color(0xFFFF3B30), green = Color(0xFF34C759), orange = Color(0xFFFF9500),
        yellow = Color(0xFFFFCC00), blue = Color(0xFF007AFF),
    )
}

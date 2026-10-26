package com.mediahub.pear.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

private val LocalPearColors = staticCompositionLocalOf { pearColors(Accents.DEFAULT_HEX, dark = true) }
private val LocalPearType = staticCompositionLocalOf { PearTypography(FontFamily.Default) }

/**
 * Read the current theme: `PearTheme.colors.accent`, `PearTheme.type.headline`.
 * Radii, spacing and motion are plain objects ([PearRadii], [PearSpacing], [PearMotion]).
 */
object PearTheme {
    val colors: PearColors
        @Composable @ReadOnlyComposable get() = LocalPearColors.current
    val type: PearTypography
        @Composable @ReadOnlyComposable get() = LocalPearType.current
}

/** Provides colours and type to everything inside. Changing [ThemeStore] updates the whole UI live. */
@Composable
fun PearTheme(store: ThemeStore, content: @Composable () -> Unit) {
    val dark = when (store.appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.System -> isSystemInDarkTheme()
    }
    val colors = remember(store.accentHex, dark) { pearColors(store.accentHex, dark) }
    val type = remember { PearTypography(pearFontFamily() ?: FontFamily.Default) }
    CompositionLocalProvider(LocalPearColors provides colors, LocalPearType provides type) {
        ProvideSystemDark(dark) { content() }
    }
}

/**
 * Re-provides the dark palette (same accent) for [content]. The player is always dark, like
 * `.preferredColorScheme(.dark)` in PlayerView.swift, whatever the app appearance is.
 */
@Composable
fun PearDark(store: ThemeStore, content: @Composable () -> Unit) {
    val colors = remember(store.accentHex) { pearColors(store.accentHex, dark = true) }
    CompositionLocalProvider(LocalPearColors provides colors) {
        ProvideSystemDark(dark = true, content = content)
    }
}

@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package com.mediahub.pear.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme

/** Haze's desktop glass reads [LocalSystemTheme] (the same signal Haze itself is wired to), so the app's choice goes there. */
@Composable
actual fun ProvideSystemDark(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light, content = content)
}

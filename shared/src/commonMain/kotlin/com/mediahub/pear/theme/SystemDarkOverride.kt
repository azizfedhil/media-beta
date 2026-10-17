package com.mediahub.pear.theme

import androidx.compose.runtime.Composable

/**
 * Makes the system light/dark signal match the app's own [dark] choice for [content]. Haze's glass reads the system
 * signal to pick its light or dark style, not the app's, so without this the glass turns light in light mode while the
 * app stays dark. Each platform provides its own signal (see the `actual`s).
 */
@Composable
expect fun ProvideSystemDark(dark: Boolean, content: @Composable () -> Unit)

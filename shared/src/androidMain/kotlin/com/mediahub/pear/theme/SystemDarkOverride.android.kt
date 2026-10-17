package com.mediahub.pear.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** Haze's glass reads the night mode from [LocalConfiguration], so the copy carries the app's choice in its uiMode. */
@Composable
actual fun ProvideSystemDark(dark: Boolean, content: @Composable () -> Unit) {
    val current = LocalConfiguration.current
    val config = remember(current, dark) {
        Configuration(current).apply {
            val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
    }
    CompositionLocalProvider(LocalConfiguration provides config, content = content)
}

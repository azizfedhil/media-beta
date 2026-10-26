package com.mediahub.pear.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.mediahub.pear.AndroidHost

/**
 * Loads whichever Archivo files are present in `androidApp/src/main/assets/fonts/` (assets of the app module are
 * visible to the whole process). None present, or assets unreadable: system default.
 */
actual fun pearFontFamily(): FontFamily? = runCatching {
    val assets = AndroidHost.app().assets
    val present = assets.list("fonts")?.toSet().orEmpty()
    val fonts = archivoFiles
        .filter { it.name in present }
        .map { Font("fonts/${it.name}", assets, it.weight, it.style) }
    if (fonts.isEmpty()) null else FontFamily(fonts)
}.getOrNull()

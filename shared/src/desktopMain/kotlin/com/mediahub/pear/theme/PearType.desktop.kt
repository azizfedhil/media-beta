package com.mediahub.pear.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

private class FontLoader

/** Loads whichever Archivo files are present in `src/desktopMain/resources/fonts/`. None present: system default. */
actual fun pearFontFamily(): FontFamily? {
    val fonts = archivoFiles.mapNotNull { f ->
        val bytes = FontLoader::class.java.getResourceAsStream("/fonts/${f.name}")?.use { it.readBytes() }
        if (bytes == null) null else Font(identity = f.name, data = bytes, weight = f.weight, style = f.style)
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

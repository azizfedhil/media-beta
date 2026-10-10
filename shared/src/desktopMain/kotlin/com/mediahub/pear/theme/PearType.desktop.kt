package com.mediahub.pear.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

private val interFiles = listOf(
    "Inter-Regular.ttf" to FontWeight.Normal,
    "Inter-Medium.ttf" to FontWeight.Medium,
    "Inter-SemiBold.ttf" to FontWeight.SemiBold,
    "Inter-Bold.ttf" to FontWeight.Bold,
    "Inter-ExtraBold.ttf" to FontWeight.ExtraBold,
    "Inter-Black.ttf" to FontWeight.Black,
)

private class InterLoader

/** Loads whichever Inter files are present in `src/desktopMain/resources/fonts/`. None present: system default. */
actual fun interFontFamily(): FontFamily? {
    val fonts = interFiles.mapNotNull { (file, weight) ->
        val bytes = InterLoader::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() }
        if (bytes == null) null else Font(identity = file, data = bytes, weight = weight, style = FontStyle.Normal)
    }
    return if (fonts.isEmpty()) null else FontFamily(fonts)
}

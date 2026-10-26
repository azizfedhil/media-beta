package com.mediahub.pear.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale. Sizes and line heights are the iOS text styles at the default Dynamic Type size, so a screen
 * laid out for the iPad app has the same proportions here. The face is Archivo.
 */
class PearTypography(family: FontFamily) {
    val largeTitle = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Normal)
    val title = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
    val title2 = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
    val title3 = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal)
    val headline = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val subheadline = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val footnote = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val caption2 = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)

    /** Name and style of every entry, for the theme preview screen. */
    fun all(): List<Pair<String, TextStyle>> = listOf(
        "largeTitle" to largeTitle, "title" to title, "title2" to title2, "title3" to title3,
        "headline" to headline, "body" to body, "callout" to callout, "subheadline" to subheadline,
        "footnote" to footnote, "caption" to caption, "caption2" to caption2,
    )
}

/** Same style, another weight (`.font(.footnote.weight(.semibold))` in SwiftUI). */
fun TextStyle.weight(weight: FontWeight): TextStyle = copy(fontWeight = weight)

/** Digits of equal width, for clocks and counters (SwiftUI `.monospacedDigit()`). */
fun TextStyle.monospacedDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

/** One bundled Archivo file: its name, and the weight and style it is registered under. */
internal class ArchivoFile(val name: String, val weight: FontWeight, val style: FontStyle = FontStyle.Normal)

/**
 * The static Archivo files the app looks for, one per weight it uses, plus the regular italic (the only italic in the
 * app, InfoCard). Desktop reads them from `shared/src/desktopMain/resources/fonts/`, Android from
 * `androidApp/src/main/assets/fonts/`.
 */
internal val archivoFiles = listOf(
    ArchivoFile("Archivo-Regular.ttf", FontWeight.Normal),
    ArchivoFile("Archivo-Medium.ttf", FontWeight.Medium),
    ArchivoFile("Archivo-SemiBold.ttf", FontWeight.SemiBold),
    ArchivoFile("Archivo-Bold.ttf", FontWeight.Bold),
    ArchivoFile("Archivo-ExtraBold.ttf", FontWeight.ExtraBold),
    ArchivoFile("Archivo-Black.ttf", FontWeight.Black),
    ArchivoFile("Archivo-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
)

/**
 * Archivo, if its files are bundled (see `shared/src/desktopMain/resources/fonts/README.md`); otherwise null and
 * the system default is used. Implemented per platform.
 */
expect fun pearFontFamily(): FontFamily?

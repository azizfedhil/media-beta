package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Keeps the window's own title bar (Windows' caption, its buttons, snap layouts) and makes it dark, so it matches the
 * app instead of the system's light theme. Windows 11 also takes the caption colour and the text colour; Windows 10
 * only gets the dark mode. Other systems keep their own bar, untouched.
 */
@Composable
fun NativeTitleBar(
    window: Window,
    background: Color = Color(0xFF09090B),
    text: Color = Color.White,
) {
    LaunchedEffect(window) {
        // The native window handle only exists once the window can be displayed.
        while (!window.isDisplayable) delay(16)
        applyNativeTitleBar(window, background, text)
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_CAPTION_COLOR = 35 // Windows 11 build 22000 and later
private const val DWMWA_TEXT_COLOR = 36 // Windows 11 build 22621 and later

private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

private object Dwm {
    /** `dwmapi.dll` ships with every Windows since Vista. Null anywhere else. */
    val api: Dwmapi? = runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
}

private fun applyNativeTitleBar(window: Window, background: Color, text: Color) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
    val api = Dwm.api ?: return
    val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
    fun set(attribute: Int, value: Int) {
        // A refused attribute (an older Windows that doesn't know it) is ignored: the bar keeps its default for that part.
        api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
    }
    set(DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
    set(DWMWA_CAPTION_COLOR, colorRef(background))
    set(DWMWA_TEXT_COLOR, colorRef(text))
}

/** Windows colours are COLORREF: 0x00BBGGRR. */
private fun colorRef(color: Color): Int {
    val r = (color.red * 255).roundToInt()
    val g = (color.green * 255).roundToInt()
    val b = (color.blue * 255).roundToInt()
    return r or (g shl 8) or (b shl 16)
}

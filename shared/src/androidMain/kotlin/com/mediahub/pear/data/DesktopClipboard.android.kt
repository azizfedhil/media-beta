package com.mediahub.pear.data

import android.content.ClipData
import android.content.ClipboardManager
import com.mediahub.pear.AndroidHost

/**
 * Android's clipboard. The object keeps the desktop's name so the call sites are the same on both platforms. Reading the
 * clipboard works while the app is in front, which is when the settings screen pastes.
 */
actual object DesktopClipboard {
    actual fun copy(text: String) {
        try {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("Pear", text))
        } catch (_: Exception) {}
    }

    actual fun paste(): String? {
        return try {
            val clip = clipboard()?.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(AndroidHost.app()).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun clipboard(): ClipboardManager? = AndroidHost.app().getSystemService(ClipboardManager::class.java)
}

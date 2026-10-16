package com.mediahub.pear.data

/**
 * The system clipboard for the text the app copies (a pairing code, the settings backup) and pastes (a backup to restore).
 * Each platform has its own actual. The name is the one the call sites already use.
 */
expect object DesktopClipboard {
    fun copy(text: String)

    fun paste(): String?
}

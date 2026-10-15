package com.mediahub.pear.data

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual object DesktopClipboard {
    actual fun copy(text: String) {
        try {
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
        } catch (_: Exception) {}
    }

    actual fun paste(): String? {
        return try {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            clip.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }
    }
}

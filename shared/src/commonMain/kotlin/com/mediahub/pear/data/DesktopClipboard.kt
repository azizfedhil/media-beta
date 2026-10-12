package com.mediahub.pear.data

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

object DesktopClipboard {
    fun copy(text: String) {
        try {
            val sel = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
        } catch (_: Exception) {}
    }

    fun paste(): String? {
        return try {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            clip.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }
    }
}
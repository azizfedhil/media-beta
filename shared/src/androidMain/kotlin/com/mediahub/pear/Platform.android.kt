package com.mediahub.pear

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import java.text.DateFormat
import java.util.Date

actual val platformName: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

actual fun currentTimeLabel(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

actual fun decodeImageBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? = com.mediahub.pear.net.decodeImageBitmap(bytes, maxEdge)

actual fun openUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AndroidHost.app().startActivity(intent)
    } catch (_: Exception) {
        // No app handles the link: nothing to show.
    }
}

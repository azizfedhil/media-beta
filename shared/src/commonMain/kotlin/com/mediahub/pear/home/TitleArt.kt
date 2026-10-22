package com.mediahub.pear.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.loadRemoteBitmap
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.LocalImageCache
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.PearText

/**
 * The title as its logo when it has one, otherwise as text (iOS `TitleArt`). The text shows from the first frame, and the logo
 * replaces it only once the logo has loaded, so a title never disappears while its logo is on the way. The logo is the add-on's
 * own when the title came with one, else TMDB's. Settings' "Logos instead of title text" turns logos off.
 */
@Composable
fun TitleArt(
    item: MetaPreview,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 200.dp,
    maxHeight: Dp = 56.dp,
    color: Color = Color.White,
) {
    val env = LocalAppEnv.current
    val http = LocalHttp.current
    val disk = LocalImageCache.current
    val titleLogos = rememberPref(env.data.prefs, PrefKeys.UI_TITLE_LOGOS) { it.bool(PrefKeys.UI_TITLE_LOGOS, true) }

    val logoUrl by produceState<String?>(initialValue = null, item.id, item.logo, titleLogos) {
        value = if (!titleLogos) null else item.logo ?: attempt { env.data.meta.tmdb.logo(item.id, item.type) }
    }
    val logo by produceState<ImageBitmap?>(initialValue = null, logoUrl) {
        value = logoUrl?.let { loadRemoteBitmap(http, it, disk = disk) }
    }

    val bitmap = logo
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = modifier.widthIn(max = maxWidth).heightIn(max = maxHeight),
        )
    } else {
        PearText(item.name, style = textStyle, color = color, maxLines = 2, modifier = modifier)
    }
}

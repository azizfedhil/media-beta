package com.mediahub.pear.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** Text in the theme's colours and type. Use this instead of Material's Text (the app does not use Material). */
@Composable
fun PearText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PearTheme.type.body,
    color: Color = PearTheme.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    /** Told the laid-out text, for example to know whether it was cut short by [maxLines]. */
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val merged = if (textAlign == null) style.copy(color = color) else style.copy(color = color, textAlign = textAlign)
    BasicText(
        text = text,
        modifier = modifier,
        style = merged,
        overflow = overflow,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
    )
}

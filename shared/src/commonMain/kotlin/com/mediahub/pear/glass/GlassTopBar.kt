package com.mediahub.pear.glass

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/**
 * The floating header of a page: optional back button, title, then [trailing] content (usually a [GlassPill]).
 * Place it over the page's scroll content (inside a [GlassScene]) so the content blurs under it.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    GlassBar(modifier.fillMaxWidth(), contentPadding = 10.dp) {
        if (onBack != null) GlassButton(onBack, symbol = "chevron.left", size = 38.dp)
        PearText(title, style = PearTheme.type.headline, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

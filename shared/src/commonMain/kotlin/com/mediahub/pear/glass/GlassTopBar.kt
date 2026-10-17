package com.mediahub.pear.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/** How far the soft edge under the bar reaches below it, in dp. */
private const val EDGE_DP = 60f

/**
 * The floating header of a page: optional back button, title, then [trailing] content (usually a [GlassPill]).
 * Place it over the page's scroll content (inside a [GlassScene]) so the content blurs under it. On the phone layout the
 * bar keeps 58 dp clear at the end for the profile avatar that floats at the top right, and a soft edge in the page colour
 * sits under it, so scrolled content does not print through the title.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val page = PearTheme.colors.background
    val endInset = if (LocalWideLayout.current) 0.dp else 58.dp
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // Always the fading edge, in every mode: a solid backdrop would hide the content scrolling under the bar.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            page,
                            page.copy(alpha = 0.95f),
                            page.copy(alpha = 0.85f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height,
                    ),
                    size = size,
                )
            }
            .padding(bottom = EDGE_DP.dp),
    ) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(end = endInset),
        ) {
            GlassBar(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
                if (onBack != null) GlassButton(onBack, symbol = "chevron.left", size = 38.dp)
                PearText(title, style = PearTheme.type.headline, maxLines = 1, modifier = Modifier.weight(1f))
                trailing()
            }
        }
    }
}
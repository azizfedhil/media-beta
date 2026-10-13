package com.mediahub.pear.home

import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.titleMenuItems
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.mediahub.pear.shell.DragLazyRow
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.WideScale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.meta.ThemeRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.ThemedTitle
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ThemeCarousel(
    row: ThemeRow,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (row.items.isEmpty()) return
    // Wide layout only: the cards are a third bigger. The phone layout keeps 1x.
    val scale = if (LocalWideLayout.current) WideScale.CARD else 1f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PearText(
            text = row.title,
            style = PearTheme.type.title2.weight(FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        DragLazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(row.items) { item ->
                ThemeCard(item = item, onClick = { onSelectTitle(item.item) }, scale = scale)
            }
        }
    }
}

/** 4:5 editorial card. */
@Composable
private fun ThemeCard(item: ThemedTitle, onClick: () -> Unit, scale: Float = 1f) {
    val width = 240.dp * scale
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value
    val hoverScale = if (hovered) 1.02f else 1f
    val env = LocalAppEnv.current
    val anchor = remember { MenuAnchor() }

    Box(
        Modifier.width(width).aspectRatio(4f / 5f)
            .graphicsLayer { scaleX = hoverScale; scaleY = hoverScale }
            .clip(RoundedCornerShape(PearRadii.card))
            .background(PearTheme.colors.fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickOrMenu(
                source,
                onClick = onClick,
                onMenu = {
                    env.shell.openContextMenu(ContextMenuRequest(anchor.rect(), titleMenuItems(env, item.item, onDetails = onClick)))
                },
            ),
    ) {
        RemoteImage(item.item.heroUrl(false) ?: item.item.backdropUrl ?: item.item.posterUrl, Modifier.fillMaxSize())

        // Bottom gradient scrim
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.4f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp * scale),
            verticalArrangement = Arrangement.spacedBy(6.dp * scale),
        ) {
            item.item.year?.let { y ->
                PearText(y.toString(), style = PearTheme.type.caption2.weight(FontWeight.SemiBold).scaledBy(scale), color = Color.White.copy(alpha = 0.8f))
            }
            TitleArt(
                item = item.item,
                textStyle = PearTheme.type.title3.weight(FontWeight.Bold).scaledBy(scale),
                maxWidth = 200.dp * scale,
                maxHeight = 56.dp * scale,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp * scale)) {
                PearIcon(if (item.item.type == "series") "tv" else "film", size = 12.dp * scale, tint = Color.White)
                val caption = (listOf(item.item.typeLabel) + item.genres).joinToString(" · ")
                PearText(caption, style = PearTheme.type.caption.scaledBy(scale), color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
    }
}

/** The same style with its type sizes multiplied by [k] (the wide layout's card scale). Returns the style as is at 1x. */
internal fun TextStyle.scaledBy(k: Float): TextStyle {
    if (k == 1f) return this
    return copy(fontSize = fontSize.scaledBy(k), lineHeight = lineHeight.scaledBy(k))
}

// TextStyle's fontSize / lineHeight default to TextUnit.Unspecified, which must be left alone.
private fun TextUnit.scaledBy(k: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (value * k).sp

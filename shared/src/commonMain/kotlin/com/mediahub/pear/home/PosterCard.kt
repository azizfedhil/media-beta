package com.mediahub.pear.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.meta.ArtworkRotation
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.NetworkBadge
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.menuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.titleMenuItems
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.core.animateFloatAsState

@Composable
fun PosterCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 104.dp,
    showsTitle: Boolean = false,
) {
    val env = LocalAppEnv.current
    val history = env.data.history
    val isWatched = history.entry(item.id)?.isTitleWatched == true
    var network by remember(item.id) { mutableStateOf<NetworkBadge?>(null) }

    LaunchedEffect(item.id) {
        if (item.type == "series" && env.data.meta.tmdb.hasKey) {
            network = env.data.meta.tmdb.network(item.id, item.type)
        }
    }

    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else if (hovered) 1.03f else 1f, PearMotion.press<Float>())
    // A left click opens the title; a right click opens its menu beside the poster (the iOS long press).
    val anchor = remember { MenuAnchor() }

    // Desktop (wide) layout shows posters 50% bigger; grids get the same through their cell size.
    val wide = LocalWideLayout.current
    val sizingModifier = if (width != null) modifier.width(if (wide) width * 1.5f else width) else modifier.fillMaxWidth()

    Column(
        modifier = sizingModifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .menuAnchor(anchor)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickOrMenu(source, onClick = onClick, onMenu = {
                env.shell.openContextMenu(ContextMenuRequest(anchor.rect(), titleMenuItems(env, item, onDetails = onClick)))
            }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(PearRadii.sm))
                .background(PearTheme.colors.fill),
        ) {
            val posterUrl = ArtworkRotation.poster(item, 0)
            RemoteImage(posterUrl, Modifier.fillMaxSize())

            // Watched checkmark badge
            if (isWatched) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    PearIcon("checkmark.circle.fill", size = 18.dp, tint = PearTheme.colors.accent)
                }
            }

            // Broadcaster / Network badge
            network?.let { net ->
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(RoundedCornerShape(PearRadii.xs))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    PearText(net.name, style = PearTheme.type.caption2.copy(fontSize = 9.sp), color = Color.White, maxLines = 1)
                }
            }
        }

        if (showsTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PearText(item.name, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), maxLines = 1)
                val caption = listOfNotNull(item.year?.toString(), item.typeLabel).joinToString(" · ")
                PearText(caption, style = PearTheme.type.caption2, color = PearTheme.colors.textSecondary, maxLines = 1)
            }
        }
    }
}
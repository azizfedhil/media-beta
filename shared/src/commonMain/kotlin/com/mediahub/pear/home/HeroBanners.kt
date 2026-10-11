package com.mediahub.pear.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay

/**
 * Compact hero carousel for phone / narrow windows with auto-advance and dots indicator.
 */
@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        while (true) {
            delay(7000)
            if (items.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % items.size
            }
        }
    }

    val item = items.getOrElse(currentIndex) { items.first() }

    Box(
        modifier
            .fillMaxWidth()
            .height(480.dp)
            .clickable { onSelectTitle(item) },
    ) {
        RemoteImage(item.heroUrl(false) ?: item.backdropUrl ?: item.posterUrl, Modifier.fillMaxSize())

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.2f to Color.Transparent,
                    0.7f to Color.Black.copy(alpha = 0.6f),
                    1.0f to Color.Black.copy(alpha = 0.95f),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(PearRadii.pill))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    PearText(item.typeLabel.uppercase(), style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
                }
                item.year?.let { y ->
                    PearText(y.toString(), style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.8f))
                }
            }

            PearText(item.name, style = PearTheme.type.largeTitle.weight(FontWeight.Bold), color = Color.White, maxLines = 2)

            item.description?.let { d ->
                PearText(d, style = PearTheme.type.subheadline, color = Color.White.copy(alpha = 0.85f), maxLines = 2)
            }

            Spacer(Modifier.height(4.dp))

            // Carousel dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { idx, _ ->
                    val isCurrent = idx == currentIndex
                    Box(
                        Modifier.height(5.dp).width(if (isCurrent) 24.dp else 6.dp)
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(if (isCurrent) Color.White else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/**
 * Wide hero panel for desktop expanded sidebar layout (spec: `WideHeroPanel` in WideLayout.swift).
 */
@Composable
fun WideHeroPanel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        while (true) {
            delay(7000)
            if (items.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % items.size
            }
        }
    }

    val item = items.getOrElse(currentIndex) { items.first() }

    Box(
        modifier
            .fillMaxHeight()
            .width(360.dp)
            .background(Color.Black)
            .clickable { onSelectTitle(item) },
    ) {
        RemoteImage(item.posterUrl ?: item.backdropUrl, Modifier.fillMaxSize())

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.3f to Color.Transparent,
                    0.65f to Color.Black.copy(alpha = 0.7f),
                    1f to Color.Black.copy(alpha = 0.95f),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(PearRadii.pill))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    PearText(item.typeLabel.uppercase(), style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White)
                }
                item.year?.let { y ->
                    PearText(y.toString(), style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.8f))
                }
            }

            PearText(item.name, style = PearTheme.type.title.weight(FontWeight.Bold), color = Color.White, maxLines = 2)

            item.description?.let { d ->
                PearText(d, style = PearTheme.type.subheadline, color = Color.White.copy(alpha = 0.8f), maxLines = 3)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { idx, _ ->
                    val isCurrent = idx == currentIndex
                    Box(
                        Modifier.height(6.dp).width(if (isCurrent) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(if (isCurrent) PearTheme.colors.accent else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}
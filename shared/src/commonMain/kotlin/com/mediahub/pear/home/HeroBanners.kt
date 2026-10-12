package com.mediahub.pear.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.rememberDominantColor
import com.mediahub.pear.shell.FadedPoster
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Computes a cinematic ambient hue from the title. */
fun fallbackHeroAmbientColor(title: String): Color {
    val h = (abs(title.hashCode()) % 360).toFloat()
    // Returns deep, cinematic moody tones (like Silo's dark teal #0E3A35)
    return Color.hsv(h, 0.70f, 0.32f)
}

/**
 * Compact hero carousel for phone / narrow layouts.
 */
@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val env = LocalAppEnv.current
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

    // Update the ambient glow
    LaunchedEffect(item.name) {
        env.shell.heroAmbientColor = fallbackHeroAmbientColor(item.name)
    }

    Box(
        modifier.fillMaxWidth().height(480.dp).clickable { onSelectTitle(item) },
    ) {
        RemoteImage(item.heroUrl(false) ?: item.backdropUrl ?: item.posterUrl, Modifier.fillMaxSize())

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.2f to Color.Transparent,
                    0.65f to Color.Black.copy(alpha = 0.65f),
                    1.0f to Color(0xFF09090B),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroMetaContent(item, onDetailsClick = { onSelectTitle(item) })

            Spacer(Modifier.height(4.dp))

            // Carousel dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { idx, _ ->
                    val isCurrent = idx == currentIndex
                    Box(
                        Modifier.height(5.dp).width(if (isCurrent) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(if (isCurrent) Color.White else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/** The wide hero column: 460 dp, 10% wider (Batch: wide-layout sizing). Phone layout doesn't use it. */
private val WideHeroWidth = 460.dp * WideScale.HERO_WIDTH

/**
 * Wide hero (the *Silo* column in the mock-up). No frame: the poster dissolves into the page's colour wash.
 * [collapse] 0..1 folds it away to nothing as the feed scrolls.
 */
@Composable
fun WideHeroPanel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    collapse: Float = 0f,
) {
    if (items.isEmpty()) return
    val env = LocalAppEnv.current
    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        while (true) {
            delay(8000)
            if (items.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % items.size
            }
        }
    }

    val item = items.getOrElse(currentIndex) { items.first() }
    val dominant = rememberDominantColor(item.posterUrl ?: item.backdropUrl)

    LaunchedEffect(item.name, dominant) {
        env.shell.heroAmbientColor = dominant ?: fallbackHeroAmbientColor(item.name)
    }

    val panelWidth = WideHeroWidth * (1f - collapse).coerceIn(0f, 1f)
    Box(modifier.fillMaxHeight().width(panelWidth).clipToBounds()) {
        // Fixed-size content so text doesn't re-wrap while the panel folds away.
        Box(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(WideHeroWidth).fillMaxHeight()
                .graphicsLayer { alpha = (1f - collapse * 1.3f).coerceIn(0f, 1f) },
        ) {
            AnimatedContent(
                targetState = item,
                transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) },
                modifier = Modifier.fillMaxSize(),
            ) { currentItem ->
                FadedPoster(currentItem.posterUrl ?: currentItem.backdropUrl, Modifier.fillMaxSize())
            }

            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 70.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeroMetaContent(item, onDetailsClick = { onSelectTitle(item) })

                Spacer(Modifier.height(2.dp))

                // Carousel dots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { idx, _ ->
                        val isCurrent = idx == currentIndex
                        Box(
                            Modifier.height(5.dp).width(if (isCurrent) 20.dp else 5.dp)
                                .clip(RoundedCornerShape(PearRadii.pill))
                                .background(if (isCurrent) Color.White else Color.White.copy(alpha = 0.40f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetaContent(item: MetaPreview, onDetailsClick: () -> Unit) {
    // [TV] SERIES · 2026 tag pill
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(PearRadii.pill))
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(PearRadii.pill))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PearIcon(if (item.type == "series") "tv" else "film", size = 11.dp, tint = Color.White)
                PearText(item.typeLabel.uppercase(), style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(fontSize = 9.sp), color = Color.White)
                item.year?.let { y ->
                    PearText(y.toString(), style = PearTheme.type.caption2.weight(FontWeight.SemiBold).copy(fontSize = 9.sp), color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }

    // Large Title Art
    PearText(
        text = item.name.uppercase(),
        style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(letterSpacing = 1.sp),
        color = Color.White,
        maxLines = 2,
    )

    // Score badge (Rotten Tomatoes tomato icon + 83%)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFFA320A)), contentAlignment = Alignment.Center) {
            PearIcon("checkmark", size = 9.dp, tint = Color.White)
        }
        val score = item.rating?.let { (it * 10).toInt().toString() + "%" } ?: "83%"
        PearText(score, style = PearTheme.type.footnote.weight(FontWeight.Bold), color = Color.White)
    }

    // Synopsis
    item.description?.let { desc ->
        PearText(desc, style = PearTheme.type.caption, color = Color.White.copy(alpha = 0.80f), maxLines = 3)
    }

    // (i) Details Button
    Row(
        Modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(PearRadii.pill))
            .clickable(onClick = onDetailsClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PearIcon("info.circle", size = 15.dp, tint = Color.White)
        PearText("Details", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = Color.White)
    }
}
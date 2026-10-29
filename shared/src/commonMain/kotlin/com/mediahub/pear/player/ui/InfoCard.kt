package com.mediahub.pear.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.detail.RatingLogo
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.pearShimmer
import com.mediahub.pear.theme.weight

/**
 * The episode or movie card (`PlayerInfoCard` in PlayerInfoCard.swift). Wide: the still on the left with the logo
 * under it, the details on the right. Narrow (under 520 dp): the still on top, the details underneath. Details are the
 * series and title, the date / runtime / rating chips, then the synopsis. No background of its own: [PlayerSurface]
 * puts it on its card.
 *
 * @param logo the show or movie logo, drawn under the still. Null when there is none.
 */
@Composable
internal fun PlayerInfoCard(
    info: PlayerInfo,
    logo: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < 520.dp
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoThumb(info, logo, stacked = true)
                InfoDetails(info, onClose, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                InfoThumb(info, logo, stacked = false)
                InfoDetails(info, onClose, Modifier.weight(1f))
            }
        }
    }
}

/** The still's slot (230 dp wide, 16:9) with the episode tag on it, and the title logo underneath. */
@Composable
private fun InfoThumb(info: PlayerInfo, logo: String?, stacked: Boolean) {
    val columnWidth = if (stacked) Modifier.fillMaxWidth() else Modifier.width(THUMB_DP.dp)
    Column(columnWidth, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only the slot and the episode tag: the picture itself is drawn by [InfoBleed] behind the whole card, so the
        // layout is the same as when the still was a rounded thumbnail here.
        if (info.image != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                info.episodeTag?.let { tag ->
                    PearText(
                        tag,
                        style = PearTheme.type.caption2.weight(FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(PearRadii.pill))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (logo != null) {
            RemoteImage(
                logo,
                Modifier.height(44.dp).widthIn(max = if (stacked) 220.dp else THUMB_DP.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun InfoDetails(info: PlayerInfo, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.kicker?.let { series ->
                    PearText(
                        series.uppercase(),
                        style = PearTheme.type.caption.weight(FontWeight.Bold).copy(letterSpacing = 0.9.sp),
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                PearText(
                    info.title,
                    style = PearTheme.type.title2.copy(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                )
            }
            // Mirrors the chevron on the title that opened this card.
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PearIcon("chevron.down", size = 13.dp, tint = Color.White)
            }
        }

        val runtime = PlayerInfoRules.runtimeText(info.runtime)
        if (info.date != null || runtime != null || info.ratings.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                info.date?.let { InfoChip(it, "calendar") }
                runtime?.let { InfoChip(it, "clock") }
                info.ratings.forEach { RatingChip(it) }
            }
        }

        Synopsis(info)
    }
}

private enum class SynopsisState { Loading, Text, Empty }

/** What the synopsis area shows. Two bodies that are equal do not animate; different ones crossfade. */
private data class SynopsisBody(val state: SynopsisState, val text: String?)

/** The synopsis: a shimmering placeholder while it loads, then the text fades in. It scrolls when longer than 124 dp. */
@Composable
private fun Synopsis(info: PlayerInfo) {
    val overview = info.overview?.takeIf { it.isNotBlank() }
    val body = when {
        overview != null -> SynopsisBody(SynopsisState.Text, overview)
        info.isLoading -> SynopsisBody(SynopsisState.Loading, null)
        else -> SynopsisBody(SynopsisState.Empty, null)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Crossfade(targetState = (info.overviewNote ?: "Synopsis").uppercase(), animationSpec = tween(PearMotion.BASE), label = "synopsis-label") { label ->
            PearText(
                label,
                style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(letterSpacing = 0.9.sp),
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
        AnimatedContent(
            targetState = body,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                fadeIn(tween(PearMotion.BASE, delayMillis = 60, easing = PearMotion.standard)) togetherWith
                    fadeOut(tween(PearMotion.FAST)) using SizeTransform(clip = false) { _, _ -> snap() }
            },
            label = "synopsis-body",
        ) { shown ->
            // Read from [shown], not from the outer values: the old body must keep its own text while it fades out.
            when (shown.state) {
                SynopsisState.Text -> Box(Modifier.heightIn(max = 124.dp).verticalScroll(rememberScrollState())) {
                    PearText(
                        shown.text.orEmpty(),
                        style = PearTheme.type.subheadline.copy(fontSize = 15.sp, lineHeight = 18.sp),
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SynopsisState.Loading -> Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf(1f, 1f, 0.6f).forEach { share ->
                        Box(
                            Modifier.fillMaxWidth(share).height(10.dp)
                                .clip(RoundedCornerShape(PearRadii.pill))
                                .pearShimmer(base = Color.White.copy(alpha = 0.08f), highlight = Color.White.copy(alpha = 0.20f)),
                        )
                    }
                }
                SynopsisState.Empty -> PearText(
                    "No synopsis available yet.",
                    style = PearTheme.type.subheadline.copy(fontStyle = FontStyle.Italic),
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, symbol: String) {
    Row(
        Modifier.clip(RoundedCornerShape(PearRadii.pill))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PearIcon(symbol, size = 13.dp, tint = Color.White.copy(alpha = 0.9f))
        PearText(text, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
    }
}

@Composable
private fun RatingChip(rating: MdbRating) {
    Row(
        Modifier.clip(RoundedCornerShape(PearRadii.pill))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RatingLogo(rating.label)
        PearText(rating.text, style = PearTheme.type.footnote.weight(FontWeight.Bold), color = Color.White, maxLines = 1)
    }
}

private const val THUMB_DP = 230

/**
 * The episode still, bleeding into the info card the way the desktop source picker's artwork does ([EpisodePane]
 * in SourcePicker.kt): drawn from the card's top-start corner and dissolving to the right and downwards into the glass.
 * It only paints, behind the card's content, and sizes itself from the card ([Modifier.matchParentSize] in
 * [PlayerSurface]), so the content's layout and the size animation are untouched. Narrow cards (the still on top of
 * the text, same 520 dp rule as [PlayerInfoCard]) let it run the full width and fade at the bottom only.
 *
 * @param contentInset the padding the card's content has inside the surface (it decides stacked vs wide).
 */
@Composable
internal fun InfoBleed(url: String?, modifier: Modifier = Modifier, contentInset: Dp = 20.dp) {
    if (url == null) return
    BoxWithConstraints(modifier.clip(RoundedCornerShape(26.dp))) {
        val stacked = maxWidth - contentInset * 2 < 520.dp
        val width = if (stacked) maxWidth else minOf(BLEED_DP.dp, maxWidth)
        Box(
            Modifier.size(width, width * (9f / 16f))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(0f to Color.Black, 0.3f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                    if (!stacked) {
                        drawRect(
                            brush = Brush.horizontalGradient(0f to Color.Black, 0.45f to Color.Black, 0.95f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) {
            RemoteImage(url, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxEdge = 1000)
            // Same top scrim the thumbnail had, so the episode tag stays readable.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.4f), 0.5f to Color.Transparent)))
            // Calms the part of the picture that sits behind the title and chips.
            if (!stacked) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
            }
        }
    }
}

private const val BLEED_DP = 350

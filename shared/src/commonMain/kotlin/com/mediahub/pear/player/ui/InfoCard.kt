package com.mediahub.pear.player.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.detail.RatingLogo
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
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

/** The still (230 dp wide, 16:9) with the episode tag on it, and the title logo underneath. */
@Composable
private fun InfoThumb(info: PlayerInfo, logo: String?, stacked: Boolean) {
    val columnWidth = if (stacked) Modifier.fillMaxWidth() else Modifier.width(THUMB_DP.dp)
    Column(columnWidth, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        info.image?.let { url ->
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(shape),
            ) {
                RemoteImage(url, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent))))
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

/** The synopsis, scrolling when it is longer than 124 dp, or the placeholder lines while it loads. */
@Composable
private fun Synopsis(info: PlayerInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PearText(
            (info.overviewNote ?: "Synopsis").uppercase(),
            style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(letterSpacing = 0.9.sp),
            color = Color.White.copy(alpha = 0.5f),
            maxLines = 1,
        )
        val overview = info.overview
        when {
            overview != null && overview.isNotBlank() -> Box(
                Modifier.heightIn(max = 124.dp).verticalScroll(rememberScrollState()),
            ) {
                PearText(
                    overview,
                    style = PearTheme.type.subheadline.copy(fontSize = 15.sp, lineHeight = 18.sp),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            info.isLoading -> Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(1f, 1f, 0.6f).forEach { share ->
                    Box(
                        Modifier.fillMaxWidth(share).height(10.dp)
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(Color.White.copy(alpha = 0.1f)),
                    )
                }
            }
            else -> PearText(
                "No synopsis available yet.",
                style = PearTheme.type.subheadline.copy(fontStyle = FontStyle.Italic),
                color = Color.White.copy(alpha = 0.5f),
            )
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

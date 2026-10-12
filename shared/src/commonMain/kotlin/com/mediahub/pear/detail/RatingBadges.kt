package com.mediahub.pear.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun RatingLogo(label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PearRadii.xs)
    when (label) {
        "IMDb" -> {
            Box(
                modifier.clip(shape).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText("IMDb", style = PearTheme.type.caption2.weight(FontWeight.Black), color = Color.Black, fontSize = 11.sp)
            }
        }
        "TMDB" -> {
            val tmdbGrad = Brush.horizontalGradient(listOf(Color(0xFF90CEA1), Color(0xFF01B4E4)))
            Box(
                modifier.clip(shape).background(tmdbGrad).padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText("TMDB", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color(0xFF0D253F), fontSize = 10.sp)
            }
        }
        "Rotten Tomatoes" -> {
            Box(
                modifier.clip(shape).background(Color(0xFFFA320A)).padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText("RT", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color.White, fontSize = 10.sp)
            }
        }
        "Metacritic" -> {
            Box(
                modifier.clip(shape).background(Color(0xFF333333)).padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText("M", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = Color(0xFFFFCC33), fontSize = 11.sp)
            }
        }
        else -> {
            Box(
                modifier.clip(shape).background(PearTheme.colors.fill).padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                PearText(label, style = PearTheme.type.caption2.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun RatingBadge(rating: MdbRating, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(PearTheme.colors.fillSubtle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RatingLogo(rating.label)
        PearText(
            text = rating.text,
            style = PearTheme.type.footnote.weight(FontWeight.Bold),
            color = PearTheme.colors.textPrimary,
        )
    }
}
package com.mediahub.pear.detail

import androidx.compose.ui.geometry.Rect
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.localParts
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlin.math.abs

enum class EpisodeFreshness(val text: String) {
    JustAired("Just aired"),
    New("New"),
}

/** Formats runtime to match iOS ("45m", "1h", "1h 15m"). */
fun formatEpisodeRuntime(runtimeMinutes: Int?): String? {
    val m = runtimeMinutes ?: return null
    if (m <= 0) return null
    return if (m >= 60) {
        if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
    } else {
        "${m}m"
    }
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** Determines if an episode recently aired (within 0–1 days for "Just aired", within 7 days for "New"). */
fun episodeFreshness(airDate: String?): EpisodeFreshness? {
    if (airDate.isNullOrEmpty()) return null
    val parts = airDate.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    val today = localParts(epochMillis())
    val todayDays = daysFromCivil(today.year, today.month, today.day)
    val airDays = daysFromCivil(parts[0], parts[1], parts[2])
    val diff = todayDays - airDays
    return when (diff) {
        0L, 1L -> EpisodeFreshness.JustAired
        in 2L..7L -> EpisodeFreshness.New
        else -> null
    }
}

/** "Oct 14, 2026" for upcoming episodes. */
fun episodeAirsOn(airDate: String?): String? {
    if (airDate.isNullOrEmpty()) return null
    val parts = airDate.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthStr = months.getOrNull(parts[1] - 1) ?: parts[1].toString()
    return "$monthStr ${parts[2]}, ${parts[0]}"
}

/**
 * Apple TV-style episode card: the still fades into a dark, softly tinted panel with
 * "EPISODE 1", the title, a short description, the runtime, and a "..." menu.
 *
 * [scale] grows the whole card in proportion (frame, padding, icons, type), so a 1.33 card looks 33% bigger rather
 * than being stretched. The phone layout uses 1.0.
 */
@Composable
fun EpisodeCard(
    ep: EpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isWatched: Boolean = false,
    isUpNext: Boolean = false,
    /** Opens the episode's menu beside the card: from a right click, or the "..." button. */
    onMenu: ((Rect) -> Unit)? = null,
    scale: Float = 1f,
) {
    fun d(v: Float): Dp = (v * scale).dp
    fun t(v: Float): TextUnit = (v * scale).sp
    val anchor = remember { MenuAnchor() }

    val cardWidth = d(250f)
    val cardHeight = d(292f)
    val stillHeight = cardHeight * 0.62f // ~181 dp at scale 1
    val radius = PearRadii.card * scale // 22 dp at scale 1

    val upcoming = ep.isUpcoming()
    val airsOn = if (upcoming) episodeAirsOn(ep.airDate) else null
    val freshness = if (!isWatched && !upcoming) episodeFreshness(ep.airDate) else null
    val runtimeText = formatEpisodeRuntime(ep.runtime)

    // Atmospheric tint derived from the artwork or title
    val atmosphericTint = remember(ep.image, ep.name) {
        val seed = ep.image ?: ep.name
        val hue = (abs(seed.hashCode()) % 360).toFloat()
        Color.hsv(hue, 0.45f, 0.35f)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverScale = if (isHovered) 1.02f else 1.0f

    val accent = PearTheme.colors.accent

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer { scaleX = hoverScale; scaleY = hoverScale }
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clip(RoundedCornerShape(radius))
            .background(Color(0xFF171717)) // iOS: Color(white: 0.09)
            .menuAnchor(anchor)
            .clickOrMenu(interactionSource, onClick = onClick, onMenu = { onMenu?.invoke(anchor.rect()) })
            .selectionGlow(isSelected, radius, accent),
    ) {
        // Atmospheric tint behind the dissolved still
        Box(
            Modifier.fillMaxSize()
                .background(atmosphericTint.copy(alpha = 0.30f)),
        )

        // Still image across top ~62% dissolving down into the dark panel
        Box(
            Modifier
                .fillMaxWidth()
                .height(stillHeight)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Linear gradient dissolve mask (black 0 -> black 0.5 -> transparent 1.0)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Black,
                            0.50f to Color.Black,
                            1.0f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            RemoteImage(
                url = ep.image,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (upcoming) {
                            alpha = 0.50f
                        }
                    },
            )
        }

        // 1.dp outer stroke border
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radius))
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.10f),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )

        // Top-start Badges (Watched / UP NEXT)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(d(10f)),
        ) {
            when {
                isWatched -> {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = d(8f), vertical = d(4f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d(4f)),
                    ) {
                        PearIcon("checkmark.circle.fill", size = d(12f), tint = PearTheme.colors.accent)
                        PearText(
                            text = "Watched",
                            style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(fontSize = t(11f)),
                            color = Color.White,
                        )
                    }
                }
                isUpNext -> {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(PearRadii.pill))
                            .background(PearTheme.colors.accent)
                            .padding(horizontal = d(8f), vertical = d(4f)),
                    ) {
                        PearText(
                            text = "UP NEXT",
                            style = PearTheme.type.caption2.weight(FontWeight.Black).copy(fontSize = t(10f), letterSpacing = t(0.8f)),
                            color = PearTheme.colors.onAccent,
                        )
                    }
                }
            }
        }

        // Top-end Rating Badge
        ep.rating?.takeIf { it > 0 }?.let { r ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(d(10f)),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(PearRadii.pill))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = d(8f), vertical = d(4f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d(5f)),
                ) {
                    RatingLogo("TMDB")
                    val scoreText = (kotlin.math.round(r * 10) / 10).toString()
                    PearText(
                        text = scoreText,
                        style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(fontSize = t(11f)),
                        color = Color.White,
                    )
                }
            }
        }

        // Bottom-start Text metadata area
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = d(16f), vertical = d(14f)),
            verticalArrangement = Arrangement.spacedBy(d(4f)),
        ) {
            // Header: "EPISODE X" + optional TOP RATED shimmer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d(6f)),
            ) {
                PearText(
                    text = "EPISODE ${ep.id}",
                    style = PearTheme.type.caption2.weight(FontWeight.SemiBold).copy(
                        fontSize = t(12f),
                        letterSpacing = t(0.8f),
                    ),
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                )

                if (ep.isTopRated) {
                    PearText(
                        text = "·",
                        style = PearTheme.type.caption2.weight(FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.40f),
                    )
                    ShimmerText(
                        text = "TOP RATED",
                        symbol = "star.fill",
                        color = Color(0xFFFFC733),
                        fontSize = t(12f),
                        tracking = t(0.8f),
                    )
                }
            }

            // Episode title
            PearText(
                text = ep.name,
                style = PearTheme.type.title3.weight(FontWeight.Bold).copy(fontSize = t(18f)),
                color = if (upcoming) Color.White.copy(alpha = 0.70f) else Color.White,
                maxLines = 1,
            )

            // Overview (up to 3 lines)
            ep.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                PearText(
                    text = overview,
                    style = PearTheme.type.caption.copy(fontSize = t(13f), lineHeight = t(17f)),
                    color = if (upcoming) Color.White.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.70f),
                    maxLines = 3,
                )
            }

            // Footer info line: "Airs on <Date>" or Freshness + Runtime
            Box(Modifier.padding(top = d(4f))) {
                if (airsOn != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d(5f)),
                    ) {
                        PearIcon("calendar", size = d(14f), tint = Color.White.copy(alpha = 0.90f))
                        PearText(
                            text = "Airs on $airsOn",
                            style = PearTheme.type.footnote.weight(FontWeight.SemiBold).copy(fontSize = t(14f)),
                            color = Color.White.copy(alpha = 0.90f),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d(6f)),
                    ) {
                        if (freshness != null) {
                            ShimmerText(
                                text = freshness.text,
                                symbol = "sparkles",
                                color = Color(0xFFFF8C80),
                                fontSize = t(14f),
                                tracking = t(0f),
                            )
                            if (runtimeText != null) {
                                PearText(
                                    text = "·",
                                    color = Color.White.copy(alpha = 0.40f),
                                )
                            }
                        }

                        runtimeText?.let { rt ->
                            PearText(
                                text = rt,
                                style = PearTheme.type.subheadline.weight(FontWeight.Medium).copy(fontSize = t(14f)),
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }

        // Bottom-end ellipsis menu button
        if (onMenu != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = d(6f), bottom = d(6f))
                    .size(width = d(44f), height = d(40f))
                    .clickable(onClick = { onMenu(anchor.rect()) }),
                contentAlignment = Alignment.Center,
            ) {
                PearIcon(
                    symbol = "ellipsis",
                    size = d(18f),
                    tint = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Animated highlight that sweeps softly across a badge text label. */
@Composable
fun ShimmerText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    tracking: TextUnit = 0.8.sp,
) {
    val transition = rememberInfiniteTransition()
    val sweep by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (symbol != null) {
                PearIcon(symbol, size = (fontSize.value + 1).dp, tint = color)
            }
            PearText(
                text = text,
                style = PearTheme.type.caption2.weight(fontWeight).copy(
                    fontSize = fontSize,
                    letterSpacing = tracking,
                ),
                color = color,
            )
        }

        Row(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val x = sweep * w
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
                            startX = x - w * 0.35f,
                            endX = x + w * 0.35f,
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (symbol != null) {
                PearIcon(symbol, size = (fontSize.value + 1).dp, tint = Color.White)
            }
            PearText(
                text = text,
                style = PearTheme.type.caption2.weight(fontWeight).copy(
                    fontSize = fontSize,
                    letterSpacing = tracking,
                ),
                color = Color.White,
            )
        }
    }
}

/** Apple-style glowing stroke selection ring. */
private fun Modifier.selectionGlow(selected: Boolean, radius: Dp, accent: Color): Modifier =
    if (!selected) this else this.drawWithContent {
        drawContent()
        val corner = CornerRadius(radius.toPx())
        val stroke = 1.25.dp.toPx()
        val k = 0.35f

        drawRoundRect(
            color = accent.copy(alpha = 0.06f * k),
            cornerRadius = corner,
            style = Stroke(width = stroke * 6f),
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.09f * k),
            cornerRadius = corner,
            style = Stroke(width = stroke * 3.6f),
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.16f * k),
            cornerRadius = corner,
            style = Stroke(width = stroke * 2f),
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.7f * k),
            cornerRadius = corner,
            style = Stroke(width = stroke),
        )
    }
package com.mediahub.pear.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.detail.RatingBadge
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.rememberDominantColor
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.animation.animateContentSize
import com.mediahub.pear.shell.LocalHttp

/** Computes a cinematic ambient hue from the title (the fallback when the artwork has no dominant colour). */
fun fallbackHeroAmbientColor(title: String): Color {
    val h = (abs(title.hashCode()) % 360).toFloat()
    // Returns deep, cinematic moody tones (like Silo's dark teal #0E3A35)
    return Color.hsv(h, 0.70f, 0.32f)
}

/** The phone hero's height. */
private val PhoneHeroHeight = 480.dp

/** The wide hero column: 460 dp, 10% wider (see WideScale). */
private val WideHeroWidth = 460.dp * WideScale.HERO_WIDTH

/** Pages change with a fade and a short slide, as the iOS hero does when it moves on. */
private val heroTransition: AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    (fadeIn(tween(900)) + slideInHorizontally(tween(900)) { it / 10 }) togetherWith
        (fadeOut(tween(900)) + slideOutHorizontally(tween(900)) { -it / 10 })
}

/**
 * The fade iOS draws with `easedFade`: the artwork stays opaque down to [start] of its height, then eases to clear at the
 * bottom along a smoothstep curve, so no line marks where the fade begins.
 */
private fun easedFade(start: Float, steps: Int = 10): Brush {
    val stops = ArrayList<Pair<Float, Color>>()
    stops += 0f to Color.Black
    for (i in 0..steps) {
        val u = i.toFloat() / steps
        val eased = u * u * (3 - 2 * u)
        stops += (start + (1f - start) * u) to Color.Black.copy(alpha = 1f - eased)
    }
    return Brush.verticalGradient(*stops.toTypedArray())
}

/**
 * The hero's page index, moved on every [intervalMillis]. While [paused] (a synopsis is open) it stays put. The timer starts
 * again when the list changes or the pause ends.
 */
@Composable
private fun rememberHeroIndex(count: Int, intervalMillis: Long, paused: Boolean): MutableIntState {
    val index = remember { mutableIntStateOf(0) }
    LaunchedEffect(count, paused) {
        if (paused || count < 2) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            index.intValue = (index.intValue + 1) % count
        }
    }
    return index
}

/**
 * Sets the glow from the hero image's own colour, as iOS does (`HomeView.updateTint`). [url] is the image the page draws. While
 * it loads, the last colour stays; nothing is guessed from the title.
 */
@Composable
private fun HeroAmbient(url: String?) {
    val env = LocalAppEnv.current
    val http = LocalHttp.current
    LaunchedEffect(url) {
        val colour = url?.let { heroAmbientFor(http, it) } ?: return@LaunchedEffect
        env.shell.heroAmbientColor = colour
        env.shell.heroAmbientKnown = true
    }
}

/**
 * The MDBList ratings of [item]: every source MDBList has, as the detail page shows them. Empty until they are known for this
 * title, so a title never shows the previous one's ratings.
 */
@Composable
private fun rememberTitleRatings(item: MetaPreview): List<MdbRating> {
    val env = LocalAppEnv.current
    var loaded by remember { mutableStateOf<Pair<String, List<MdbRating>>?>(null) }
    LaunchedEffect(item.id) {
        val mdb = env.data.meta.mdblist
        // MDBList is keyed by IMDb id. A TMDB item's id is resolved through TMDB, as the detail page does.
        val imdb = if (item.id.startsWith("tt")) item.id
        else item.id.removePrefix("tmdb:").toIntOrNull()?.let { attempt { env.data.meta.tmdb.imdbId(it, item.type) } }
        val ratings = if (imdb != null && mdb.hasKey) attempt { mdb.ratings(imdb, item.type) } ?: emptyList() else emptyList()
        loaded = item.id to ratings
    }
    return loaded?.takeIf { it.first == item.id }?.second.orEmpty()
}

/**
 * The artwork of a hero page: a slow zoom while the page is showing (iOS's Ken Burns), a parallax drift against the scroll
 * ([scroll]), and an eased fade into the page at the bottom. [fadeSides] also dissolves the sides, as the wide column does.
 */
@Composable
private fun HeroArt(
    url: String?,
    active: Boolean,
    scroll: ScrollState?,
    fadeSides: Boolean,
    modifier: Modifier = Modifier,
) {
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(active) {
        // Slow and linear while the page shows; back to normal quickly when it leaves.
        if (active) zoom.animateTo(1.1f, tween(durationMillis = 18_000, easing = LinearEasing))
        else zoom.animateTo(1f, tween(durationMillis = 900))
    }
    val bottomFade = remember { easedFade(start = 0.42f) }
    val sideFade = remember {
        Brush.horizontalGradient(0f to Color.Transparent, 0.16f to Color.Black, 0.60f to Color.Black, 1f to Color.Transparent)
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (fadeSides) drawRect(sideFade, blendMode = BlendMode.DstIn)
                drawRect(bottomFade, blendMode = BlendMode.DstIn)
            },
    ) {
        // Taller than the window by an eighth at each end, so the drift never shows an edge.
        val overscan = maxHeight * 0.125f
        Box(
            Modifier.fillMaxWidth().height(maxHeight * 1.25f).offset(y = -overscan).graphicsLayer {
                // Half the scroll speed: the artwork drifts behind the content, which is the depth iOS's parallax gives.
                translationY = min((scroll?.value ?: 0) * 0.5f, overscan.toPx())
            },
        ) {
            RemoteImage(url, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value })
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
                ),
            )
        }
    }
}

/**
 * The text over a hero page: the type and year, the title (its logo, or its name until the logo loads), the MDBList ratings,
 * the synopsis and Details. The synopsis shows three lines in both layouts, with More and Less when it is cut short. [expanded]
 * and [onToggleExpanded] are the carousel's, so opening a synopsis can pause the carousel.
 */
@Composable
private fun HeroInfo(
    modifier: Modifier,
    item: MetaPreview,
    ratings: List<MdbRating>,
    wide: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDetails: () -> Unit,
) {
    var overflows by remember(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp)) {
        HeroTypePill(item, wide)
        TitleArt(
            item = item,
            textStyle = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = if (wide) 40.sp else 32.sp, letterSpacing = 1.sp),
            maxWidth = if (wide) 420.dp else 300.dp,
            maxHeight = if (wide) 96.dp else 72.dp,
        )
        if (ratings.isNotEmpty()) {
            // Every MDBList source, in a row that scrolls when more than fit: nothing is cut off without a way to reach it.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ratings.forEach { RatingBadge(it) }
            }
        }
        item.description?.takeIf { it.isNotBlank() }?.let { desc ->
            PearText(
                text = desc,
                style = PearTheme.type.subheadline.weight(FontWeight.Medium).copy(
                    fontSize = if (wide) 17.sp else 15.sp,
                    lineHeight = if (wide) 23.sp else 20.sp,
                ),
                color = Color.White.copy(alpha = 0.92f),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (overflows || expanded) {
                PearText(
                    text = if (expanded) "Less" else "More",
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.clickable(onClick = onToggleExpanded),
                )
            }
        }
        HeroDetailsButton(onDetails)
    }
}

/** "TV SERIES · 2026" as a pill. The background comes before the padding, so the fill covers the padding too. */
@Composable
private fun HeroTypePill(item: MetaPreview, wide: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = if (wide) 10.dp else 8.dp, vertical = if (wide) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PearIcon(if (item.type == "series") "tv" else "film", size = if (wide) 13.dp else 11.dp, tint = Color.White)
        PearText(
            item.typeLabel.uppercase(),
            style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(fontSize = if (wide) 12.sp else 9.sp),
            color = Color.White,
        )
        item.year?.let { y ->
            PearText(
                y.toString(),
                style = PearTheme.type.caption2.weight(FontWeight.SemiBold).copy(fontSize = if (wide) 13.sp else 9.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The hero's Details button: a solid accent pill with the accent's own text colour. The padding comes before the background,
 * so the accent fills the whole pill rather than only the text inside it.
 */
@Composable
private fun HeroDetailsButton(onClick: () -> Unit) {
    val c = PearTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(c.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PearIcon("info.circle", size = 16.dp, tint = c.onAccent)
        PearText("Details", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = c.onAccent)
    }
}

/** The dots under the hero: the current page is long and white. Tapping one shows that page. */
@Composable
private fun HeroDots(count: Int, index: Int, wide: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            key(i) {
                val current = i == index
                val width by animateDpAsState(
                    targetValue = if (current) (if (wide) 22.dp else 20.dp) else 6.dp,
                    animationSpec = tween(durationMillis = 400),
                )
                Box(
                    Modifier.height(5.dp).width(width)
                        .clip(RoundedCornerShape(PearRadii.pill))
                        .background(if (current) Color.White else Color.White.copy(alpha = 0.40f))
                        .clickable { onSelect(i) },
                )
            }
        }
    }
}

/**
 * The phone hero: a page for each title, which moves on every seven seconds. Each page's artwork zooms slowly while it shows,
 * drifts against the scroll ([scroll], the Home column's) and melts into the page below, so the ambient glow shows through.
 */
@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState? = null,
) {
    if (items.isEmpty()) return
    // The page whose synopsis is open, or null. An open synopsis pauses the carousel; a page change closes it.
    var expandedId by remember { mutableStateOf<String?>(null) }
    val index = rememberHeroIndex(items.size, intervalMillis = 7_000L, paused = expandedId != null)
    LaunchedEffect(index.intValue) { expandedId = null }
    val current = items.getOrElse(index.intValue) { items.first() }
    HeroAmbient(current.heroUrl(false) ?: current.backdropUrl ?: current.posterUrl)

    Box(modifier.fillMaxWidth().height(PhoneHeroHeight)) {
        AnimatedContent(
            targetState = index.intValue,
            transitionSpec = heroTransition,
            modifier = Modifier.fillMaxSize(),
            label = "hero-phone",
        ) { i ->
            val item = items.getOrElse(i) { items.first() }
            Box(Modifier.fillMaxSize().clickable { onSelectTitle(item) }) {
                HeroArt(
                    url = item.heroUrl(false) ?: item.backdropUrl ?: item.posterUrl,
                    active = i == index.intValue,
                    scroll = scroll,
                    fadeSides = false,
                    modifier = Modifier.fillMaxSize(),
                )
                HeroInfo(
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
                    item = item,
                    ratings = rememberTitleRatings(item),
                    wide = false,
                    expanded = expandedId == item.id,
                    onToggleExpanded = { expandedId = if (expandedId == item.id) null else item.id },
                    onDetails = { onSelectTitle(item) },
                )
            }
        }
        HeroDots(
            count = items.size,
            index = index.intValue,
            wide = false,
            onSelect = { index.intValue = it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
    }
}

/**
 * The wide hero: the tall column on the left of the Home page. It moves on every eight seconds, with the same zoom, parallax
 * and fades as the phone hero, and a short fade at the bottom so the text reads over the artwork. [collapse] 0..1 folds it
 * away to nothing as the feed scrolls; [scroll] is the feed's scroll, which the artwork drifts against.
 */
@Composable
fun WideHeroPanel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    collapse: Float = 0f,
    scroll: ScrollState? = null,
) {
    if (items.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(null) }
    val index = rememberHeroIndex(items.size, intervalMillis = 8_000L, paused = expandedId != null)
    LaunchedEffect(index.intValue) { expandedId = null }
    val current = items.getOrElse(index.intValue) { items.first() }
    HeroAmbient(current.largePosterUrl() ?: current.backdropUrl)

    val panelWidth = WideHeroWidth * (1f - collapse).coerceIn(0f, 1f)
    Box(modifier.fillMaxHeight().width(panelWidth).clipToBounds()) {
        // Fixed-size content so the text doesn't re-wrap while the panel folds away.
        Box(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(WideHeroWidth).fillMaxHeight()
                .graphicsLayer { alpha = (1f - collapse * 1.3f).coerceIn(0f, 1f) },
        ) {
            AnimatedContent(
                targetState = index.intValue,
                transitionSpec = heroTransition,
                modifier = Modifier.fillMaxSize(),
                label = "hero-wide",
            ) { i ->
                val item = items.getOrElse(i) { items.first() }
                Box(Modifier.fillMaxSize().clickable { onSelectTitle(item) }) {
                    HeroArt(
                        url = item.largePosterUrl() ?: item.backdropUrl,
                        active = i == index.intValue,
                        scroll = scroll,
                        fadeSides = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A short fade into the bottom, so the title, ratings and synopsis read over the artwork.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)),
                        ),
                    )
                    HeroInfo(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 40.dp, bottom = 74.dp),
                        item = item,
                        ratings = rememberTitleRatings(item),
                        wide = true,
                        expanded = expandedId == item.id,
                        onToggleExpanded = { expandedId = if (expandedId == item.id) null else item.id },
                        onDetails = { onSelectTitle(item) },
                    )
                }
            }
            HeroDots(
                count = items.size,
                index = index.intValue,
                wide = true,
                onSelect = { index.intValue = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
            )
        }
    }
}
